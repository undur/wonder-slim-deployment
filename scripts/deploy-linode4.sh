#!/usr/bin/env bash
# Deploy wotaskd and JavaMonitor to linode-4.rebbi.is.
#
# linode-4's layout differs from hz1's:
#   - wotaskd lives in /opt/Apple/Library/WebObjects/JavaApplications/,
#     launched by the SysV script /etc/init.d/webobjects
#   - JavaMonitor lives in /rebbi/is.rebbi.javamonitor/wo/ and is
#     wotaskd-MANAGED (a registered app, instance 2 on port 2021, with
#     autoRecover=YES) — so it is updated by swapping the bundle and
#     killing the process; wotaskd relaunches it from the new bundle
#
# Steps:
#   1. Local: install fresh sjip-core
#   2. Local: package wotaskd and JavaMonitor (pinned to /opt/jdk-26)
#   3. Server: move the existing bundles aside (x-*-prev-STAMP, matching
#      the box's convention), upload the new ones
#   4. Server: restart wotaskd (service webobjects) — apps keep running;
#      the config is empty for ~30-40s until lifebeats re-register
#   5. Server: kill the JavaMonitor process; wotaskd autoRecover
#      relaunches it from the new bundle
set -euo pipefail

SERVER="root@linode-4.rebbi.is"
WOTASKD_DIR="/opt/Apple/Library/WebObjects/JavaApplications"
JAVAMONITOR_DIR="/rebbi/is.rebbi.javamonitor/wo"
JVM_PATH="/opt/jdk-26/bin/java"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"

cd "${REPO_ROOT}"

echo "==> [1/5] Building & installing sjip-core"
( cd sjip-core && mvn -q -DskipTests clean install )

echo "==> [2/5] Packaging wotaskd and JavaMonitor (jvm=${JVM_PATH})"
( cd wotaskd && mvn -q -DskipTests clean package "-Dlaunch.jvm=${JVM_PATH}" )
( cd JavaMonitor && mvn -q -DskipTests clean package "-Dlaunch.jvm=${JVM_PATH}" )

for APP in wotaskd JavaMonitor; do
	if [ ! -d "${REPO_ROOT}/${APP}/target/${APP}.woa" ]; then
		echo "Build did not produce ${REPO_ROOT}/${APP}/target/${APP}.woa" >&2
		exit 1
	fi
done

echo "==> [3/5] Moving remote bundles aside and uploading (stamp ${STAMP})"
ssh "${SERVER}" "mv '${WOTASKD_DIR}/wotaskd.woa' '${WOTASKD_DIR}/x-wotaskd.woa-prev-${STAMP}'
	ls -d '${WOTASKD_DIR}/x-wotaskd.woa-prev-'* 2>/dev/null | sort | head -n -1 | xargs -r rm -rf"
scp -q -r "${REPO_ROOT}/wotaskd/target/wotaskd.woa" "${SERVER}:${WOTASKD_DIR}/wotaskd.woa"
ssh "${SERVER}" "chmod -R 777 '${WOTASKD_DIR}/wotaskd.woa'"

ssh "${SERVER}" "mv '${JAVAMONITOR_DIR}/JavaMonitor.woa' '${JAVAMONITOR_DIR}/x-JavaMonitor.woa-prev-${STAMP}'
	ls -d '${JAVAMONITOR_DIR}/x-JavaMonitor.woa-prev-'* 2>/dev/null | sort | head -n -1 | xargs -r rm -rf"
scp -q -r "${REPO_ROOT}/JavaMonitor/target/JavaMonitor.woa" "${SERVER}:${JAVAMONITOR_DIR}/JavaMonitor.woa"
ssh "${SERVER}" "chmod -R 777 '${JAVAMONITOR_DIR}/JavaMonitor.woa'"

echo "==> [4/5] Restarting wotaskd (service webobjects)"
ssh "${SERVER}" "service webobjects stop; sleep 2; service webobjects start"

echo "==> [5/5] Restarting JavaMonitor (kill; wotaskd autoRecover relaunches)"
ssh "${SERVER}" "JM_PID=\$(ps aux | awk '/JavaMonitor.woa/ && /java/ && !/awk/ {print \$2}' | head -1)
	if [ -n \"\${JM_PID}\" ]; then kill \"\${JM_PID}\"; echo \"killed JavaMonitor pid \${JM_PID}\"; else echo 'JavaMonitor was not running'; fi"

echo "==> Done. Previous bundles preserved as x-*-prev-${STAMP} on the server."
echo "    Verify: config repopulated at http://linode-4.rebbi.is:1085/cgi-bin/WebObjects/wotaskd.woa/wa/woconfig"
