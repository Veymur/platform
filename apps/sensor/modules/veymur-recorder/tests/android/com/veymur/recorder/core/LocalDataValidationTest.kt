package com.veymur.recorder.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veymur.recorder.core.localdata.*
import com.veymur.recorder.core.localdata.aggregates.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/** A persisted synthetic recording, not evidence about BLE or a phone's background behavior. */
internal object LocalValidationFixture {
    fun context(prefix: String): Context {
        require(prefix.matches(Regex("local-validation-[a-zA-Z0-9-]+")))
        return object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix+name)
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(prefix+name,mode)
            override fun getCacheDir() = File(baseContext.cacheDir,prefix).also { it.mkdirs() }
        }
    }
    val parameters = AggregateParameters(3000,30.0,5000,20.0,2000)
    fun op(j: Journal, operation: String, vararg args: Pair<String,Any?>): Map<String,Any?> = j.localData(JSONObject().put("operation",operation).apply { args.forEach { put(it.first,it.second ?: JSONObject.NULL) } })
    @Suppress("UNCHECKED_CAST") fun stream(j: Journal,id: String,kind: String): List<Map<String,Any?>> {
        val rows=mutableListOf<Map<String,Any?>>()
        var after=0L
        do {
            val page=op(j,"stream","id" to id,"stream" to kind,"after_sequence" to after,"limit" to 2)
            rows.addAll(page["items"] as List<Map<String,Any?>>)
            val next=page["next_sequence"] as? Long ?: break
            assertTrue(next>after); after=next
        } while(true)
        return rows
    }
    fun digest(rows: List<Map<String,Any?>>): String = MessageDigest.getInstance("SHA-256")
        .digest(rows.sortedBy { (it["sequence"] as Number).toLong() }.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun db(j: Journal) = Journal::class.java.getDeclaredField("db").apply { isAccessible=true }.get(j) as SQLiteDatabase
    fun hr(j: Journal,id: String,s: JournalStamp,bpm: Int,normalizer: ObservationNormalizer=ObservationNormalizer()) {
        val sample=normalizer.hr(bpm,true,true,Receipt(s.utcMs,s.elapsedNs,s.timebaseId,s.timezoneId))
        j.append(id,"hr",JSONObject().put("bpm",bpm).put("contact_supported",true).put("contact",true).put("observation_metadata",observationMetadata(sample.time,sample.quality)))
    }
}

@RunWith(AndroidJUnit4::class)
class LocalDataValidationTest {
    @Test fun persistedCombinedStreamsClockJumpPauseLossAndRangesReproduceAfterReopen() {
        val ctx=LocalValidationFixture.context("local-validation-${UUID.randomUUID()}-")
        var ms=1000L; var wallOffset=0L; var zone="UTC"
        fun stamp()=JournalStamp(1700000000000+ms+wallOffset,ms*1_000_000,"fixture-run",zone)
        val id: String; val expected: SessionAggregates; val original: List<Map<String,Any?>>
        Journal(ctx,::stamp).use { j ->
            val ranges=JSONObject("""{"ranges":[{"id":"low","label":"L","lower_bpm_inclusive":0,"upper_bpm_exclusive":100},{"id":"mid","label":"M","lower_bpm_inclusive":100,"upper_bpm_exclusive":120},{"id":"high","label":"H","lower_bpm_inclusive":120,"upper_bpm_exclusive":null}]}""")
            val saved=LocalValidationFixture.op(j,"saveRanges","expected_revision" to 0,"value" to ranges)["hr_ranges"] as Map<*,*>
            LocalValidationFixture.op(j,"saveSettings","expected_revision" to 0,"value" to JSONObject("""{"units":"metric","timezone_id":"UTC","theme":"system","route_enabled":true,"hr_ranges_id":"${saved["id"]}","alerts":{"enabled":false,"sound":false,"vibration":false,"debounce_ms":0,"hysteresis_bpm":0,"repeat_ms":null}}"""))
            id=j.begin("SYNTHETIC",true)
            val normalizer=ObservationNormalizer()
            fun hr(bpm: Int)=LocalValidationFixture.hr(j,id,stamp(),bpm,normalizer)
            fun gps(longitude: Double) {
                val s=stamp(); val sample=normalizer.gps(GpsInput(0.0,longitude,5.0,null,null,s.utcMs,s.elapsedNs,false),Receipt(s.utcMs,s.elapsedNs,s.timebaseId,s.timezoneId))
                j.append(id,"gps",JSONObject().put("latitude",0.0).put("longitude",longitude).put("accuracy_m",5.0).put("observation_metadata",observationMetadata(sample.time,sample.quality)))
            }
            hr(80); gps(0.0)
            ms=2000; wallOffset=-3_600_000; zone="Europe/Warsaw"; hr(100); gps(0.00001)
            ms=3000; j.append(id,"disconnected",JSONObject()); gps(0.00002)
            ms=4000; j.transition(id,"pause","paused",3000)
            ms=10000; j.transition(id,"resume","recording",3000); hr(120); gps(1.0)
            ms=11000; gps(1.00001)
            ms=12000; j.append(id,"gps_unavailable",JSONObject())
            ms=13000; gps(1.00002)
            ms=14000; hr(100)
            ms=15000; j.transition(id,"finish","completed",8000)
            val result=j.recalculate(id,LocalValidationFixture.parameters)
            assertTrue(result.unavailable_reasons.isEmpty()); expected=result.aggregates!!
            assertEquals(8000.0,expected.active_duration_ms,0.0)
            assertEquals(6000.0,expected.hr_covered_duration_ms,0.0); assertEquals(0.75,expected.hr_coverage!!,0.0)
            assertEquals(640.0/6,expected.average_hr_bpm!!,1e-10)
            assertEquals(listOf(1000.0,2000.0,3000.0),expected.range_durations.map { it.duration_ms })
            assertEquals(3,expected.route_segments.size)
            assertEquals(3*SPHERE_RADIUS_M*Math.PI/180*0.00001,expected.gps_distance_m!!,1e-7)
            assertTrue(expected.hr_intervals.none { it.start_elapsed_ns.toLong()<10_000_000_000 && it.end_elapsed_ns.toLong()>4_000_000_000 })
            original=listOf("hr","gps","events").flatMap { LocalValidationFixture.stream(j,id,it) }
            val changed=JSONObject(LocalValidationFixture.stream(j,id,"hr")[1]["payload_json"] as String).getJSONObject("observation_metadata")
            assertTrue(changed.getJSONArray("quality_reasons").toString().contains("wall_clock_discontinuity"))
            assertTrue(changed.isNull("observed_at_utc"))
        }
        Journal(ctx,::stamp).use { j ->
            assertEquals(expected,j.recalculate(id,LocalValidationFixture.parameters).aggregates)
            val reopened=listOf("hr","gps","events").flatMap { LocalValidationFixture.stream(j,id,it) }
            assertEquals(LocalValidationFixture.digest(original),LocalValidationFixture.digest(reopened))
            val p=JSONObject("""{"hr_stale_after_ms":3000,"gps_max_accuracy_m":30,"gps_max_gap_ms":5000,"gps_max_speed_m_s":20,"map_hr_window_ms":2000}""")
            val details=LocalValidationFixture.op(j,"details","id" to id,"parameters" to p)
            assertEquals(0.75,(details["summary"] as Map<*,*>)["hr_coverage"])
            val other=j.begin("OTHER",false); j.transition(other,"finish","completed",0)
            val export=j.export(id)
            assertEquals(true,LocalValidationFixture.op(j,"delete","id" to id)["data_deleted"]); assertFalse(export.exists())
            assertTrue(LocalValidationFixture.stream(j,other,"events").isNotEmpty())
        }
    }

    /** Separate process phases: an external driver must force-stop a dedicated emulator host. */
    @Test fun committedPrefixAndUncommittedRollbackAfterExternalProcessDeath() {
        val phase=InstrumentationRegistry.getArguments().getString("localCrashPhase")
        assumeTrue("Requires external prepare/force-stop/verify protocol",phase=="prepare" || phase=="verify")
        val host=InstrumentationRegistry.getInstrumentation().targetContext
        val marker=File(host.getExternalFilesDir(null),"local-data-death-ready.json")
        if(phase=="prepare") {
            val prefix="local-validation-${UUID.randomUUID()}-"
            var ms=1000L
            fun stamp()=JournalStamp(1700000000000+ms,ms*1_000_000,"before-kill","UTC")
            val j=Journal(LocalValidationFixture.context(prefix),::stamp)
            val id=j.begin("SYNTHETIC",false)
            ms=2000; LocalValidationFixture.hr(j,id,stamp(),80)
            ms=3000; LocalValidationFixture.hr(j,id,stamp(),100)
            val rows=listOf("hr","events").flatMap { LocalValidationFixture.stream(j,id,it) }
            // Hold an outer transaction open: the native append must not survive process death.
            LocalValidationFixture.db(j).beginTransaction()
            ms=4000; LocalValidationFixture.hr(j,id,stamp(),200)
            marker.writeText(JSONObject().put("prefix",prefix).put("session_id",id).put("digest",LocalValidationFixture.digest(rows)).put("count",rows.size).toString())
            CountDownLatch(1).await(45,TimeUnit.SECONDS)
            fail("External driver did not terminate the prepared process")
        } else {
            val m=JSONObject(marker.readText())
            Journal(LocalValidationFixture.context(m.getString("prefix")),{JournalStamp(1800000000000,100_000_000,"after-kill","UTC")}).use { j ->
                val id=m.getString("session_id")
                val before=listOf("hr","events").flatMap { LocalValidationFixture.stream(j,id,it) }
                assertEquals(m.getInt("count"),before.size); assertEquals(m.getString("digest"),LocalValidationFixture.digest(before))
                assertEquals(1,j.recoverInterrupted()); assertEquals(0,j.recoverInterrupted())
                assertEquals(2,LocalValidationFixture.stream(j,id,"hr").size)
                val a=j.recalculate(id,LocalValidationFixture.parameters).aggregates!!
                assertTrue(a.interrupted_tail_unknown); assertEquals(2000.0,a.active_duration_ms,0.0)
                assertEquals(1000.0,a.hr_covered_duration_ms,0.0); assertEquals(80.0,a.average_hr_bpm!!,0.0)
                val page=LocalValidationFixture.op(j,"list","limit" to 10,"before" to null)
                assertEquals("interrupted",((page["items"] as List<*>).single() as Map<*,*>)["state"])
                assertEquals(true,LocalValidationFixture.op(j,"delete","id" to id)["data_deleted"])
            }
            marker.delete()
        }
    }
}
