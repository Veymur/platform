# Synthetic contract examples

`contract-examples.json` contains one independent example per schema variant, a processing example, and a manifest for a separate empty capture. IDs, addresses, timestamps, values and expectations are invented. The file is not a captured archive or a replay fixture and contains no device recording.

Run `npm test` from `apps/sensor` to validate every example with draft-07 JSON Schema, including missing-field, unknown-version, numeric precision and null/absent checks. Tests reuse the Ajv dependency already pinned in the existing ESLint lock graph; no runtime dependency was added.

Native instrumentation independently calls all SDK callback signatures with synthetic arguments and writes `polar-d2-synthetic-contract.json` to the dedicated test emulator's app external files directory for host schema verification. This generated file is not automatically added to Git.

Hardware fixtures and provenance-preserving conversion remain D6/D8 tasks. Do not add original personal recordings here.
