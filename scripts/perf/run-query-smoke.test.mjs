import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const wrapper = new URL("./run-query-smoke.sh", import.meta.url);

test("keeps credentials out of the sampler and disables k6 debug sinks", async () => {
    const source = await readFile(wrapper, "utf8");

    assert.match(source, /unset K6_HTTP_DEBUG K6_CONSOLE_OUTPUT K6_LOG_OUTPUT/);
    assert.match(source, /-u DWARVENPICK_PASSWORD/);
    assert.match(source, /-u SQL_MIX/);
    assert.match(source, /run --log-output=none/);
});
