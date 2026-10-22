package com.veymur.recorder.core.localdata.export

import com.veymur.recorder.core.localdata.QualityFlag
import com.veymur.recorder.core.localdata.SessionEventPayload
import com.veymur.recorder.core.localdata.GpsState
import com.veymur.recorder.core.localdata.aggregates.AggregateFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class UserExportFormatsTest {
    private fun csvRows(text: String) = text.trimEnd('\n').split('\n').map { it.split(',') }

    @Test fun pulseCsvKeepsEveryObservationMarksUsabilityAndContinuesActiveTimeAcrossPause() {
        val fixture = AggregateFixture().apply {
            start(); hr(0, 80); hr(1000, 82); hr(2000, 84, listOf(QualityFlag.valid_format, QualityFlag.suspect_signal))
            pause(3000); resume(10000); hr(10500, 90); finish(20000)
        }
        val aggregates = fixture.aggregate()
        val out = StringBuilder()
        val written = writePulseCsv(fixture.session(), fixture.hr, aggregates, out)
        val rows = csvRows(out.toString())
        assertEquals(PULSE_CSV_HEADER, rows.first().joinToString(","))
        assertEquals(4, written)
        assertEquals(5, rows.size)
        val body = rows.drop(1)
        assertEquals(listOf("1", "1", "1", "2"), body.map { it[1] })
        assertEquals(listOf("0.000", "1.000", "2.000", "3.500"), body.map { it[4] })
        assertEquals(listOf("80", "82", "84", "90"), body.map { it[5] })
        assertEquals(listOf("true", "true", "false", "true"), body.map { it[6] })
        assertEquals(listOf("1.000", "2.000", "", "8.500"), body.map { it[7] })
        assertEquals("source_suspect_signal", body[2][8])
        assertTrue(body.all { it[0] == "session-1" })
    }

    @Test fun pulseCsvNeverInventsRowsForMissingPulse() {
        val fixture = AggregateFixture().apply {
            start(); hr(0, 80); event(500, SessionEventPayload.HrStateChanged(com.veymur.recorder.core.localdata.HrState.lost, "disconnected")); hr(30000, 81); finish(31000)
        }
        val out = StringBuilder()
        writePulseCsv(fixture.session(), fixture.hr, fixture.aggregate(), out)
        val body = csvRows(out.toString()).drop(1)
        assertEquals(2, body.size)
        assertEquals("0.500", body[0][7])
        assertEquals("30.000", body[1][4])
    }

    @Test fun routeGpxKeepsSegmentsSeparateAndAttachesOnlyMatchedPulse() {
        val fixture = AggregateFixture(route = true).apply {
            start(); hr(0, 80); gps(100); gps(1000)
            event(1500, SessionEventPayload.GpsStateChanged(GpsState.lost, "provider_off"))
            gps(2000); gps(3000); pause(3500); resume(5000); gps(5100); finish(6000)
        }
        val aggregates = fixture.aggregate()
        val out = StringBuilder()
        val points = writeRouteGpx(fixture.session(), fixture.gps, aggregates, out)
        assertEquals(5, points)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(out.toString().byteInputStream())
        val segments = document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg")
        assertEquals(3, segments.length)
        val hr = document.getElementsByTagNameNS("http://www.garmin.com/xmlschemas/TrackPointExtension/v1", "hr")
        assertEquals(3, hr.length)
        assertEquals("80", hr.item(0).textContent)
        val trackPoints = document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        val withPulse = (0 until trackPoints.length).map { index ->
            (trackPoints.item(index) as org.w3c.dom.Element).getElementsByTagNameNS("http://www.garmin.com/xmlschemas/TrackPointExtension/v1", "hr").length == 1
        }
        assertEquals(listOf(true, true, true, false, false), withPulse)
        assertTrue(out.contains("lat=\"0.0000000\" lon=\"0.0000010\""))
    }

    @Test fun routeGpxIsEmptyWithoutUsableRoute() {
        val fixture = AggregateFixture(route = true).apply { start(); hr(0, 80); gps(100, accuracy = 99.0); finish(1000) }
        val out = StringBuilder()
        assertEquals(0, writeRouteGpx(fixture.session(), fixture.gps, fixture.aggregate(), out))
        assertEquals("", out.toString())
    }

    @Test fun csvFieldsAndXmlTextAreEscaped() {
        val fixture = AggregateFixture().apply { start(); hr(0, 80); finish(1000) }
        val quoted = fixture.hr.map { it.copy(envelope = it.envelope.copy(recorded_at_utc = "2026,\"x\"")) }
        val out = StringBuilder()
        writePulseCsv(fixture.session(), quoted, fixture.aggregate(), out)
        assertTrue(out.contains(",\"2026,\"\"x\"\"\","))
        assertFalse(out.contains("\r"))
    }

    @Test fun activeTimelineRejectsTimesOutsideSegments() {
        val aggregates = AggregateFixture().apply { start(1000); hr(1000, 80); finish(2000) }.aggregate()
        val timeline = ActiveTimeline(aggregates.active_segments)
        assertEquals(null, timeline.activeSeconds("segment-1", 999_000_000))
        assertEquals(1.0, timeline.activeSeconds("segment-1", 2_000_000_000)!!, 0.0)
        assertEquals(null, timeline.segmentNumber("unknown"))
    }
}
