#!/bin/sh
# CI only: the real Go relay on this machine + the lab app on the emulator (10.0.2.2), and the test plays Claude.
cd "$(dirname "$0")/../.." || exit 1
go build -o /tmp/roninrelay ./cmd/roninrelay || exit 1
rm -rf /tmp/rr
PUB=$(/tmp/roninrelay -data /tmp/rr -pubkey | base64 -w0)
nohup /tmp/roninrelay -data /tmp/rr -addr 0.0.0.0:8790 -public http://10.0.2.2:8790 -pair 30m > /tmp/relay.log 2>&1 &
sleep 2
cd android || exit 1
timeout 15m ./gradlew --no-daemon connectedLabDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ronin.browser.agent.AgentLinkE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.relayUrl=http://10.0.2.2:8790 \
  -Pandroid.testInstrumentationRunnerArguments.relayPub="$PUB"
R=$?
echo "---- relay log ----"; tail -40 /tmp/relay.log; tail -40 /tmp/rr/audit.log 2>/dev/null
exit $R
