#!/usr/bin/env bash
# Deploy wotaskd and JavaMonitor to linode-4.rebbi.is.
#
# Since 2026-08-30 linode-4 uses the standard hz1 layout: both bundles
# in /opt/webobjects/apps, systemd services wotaskd + javamonitor,
# SiteConfig in /opt/webobjects/conf. Same steps as deploy.sh.
set -euo pipefail

SERVER="root@linode-4.rebbi.is"
WOTASKD_DIR="/opt/webobjects/apps"
JAVAMONITOR_DIR="/opt/webobjects/apps"
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

echo "==> [4/5] Restarting javamonitor"
ssh "${SERVER}" "systemctl restart javamonitor"

echo "==> [5/5] Restarting wotaskd (apps keep running; lifebeats repopulate within ~40s)"
ssh "${SERVER}" "systemctl restart wotaskd"

echo "==> Done. Previous bundles preserved as x-*-prev-${STAMP} on the server."
echo "    Verify: config repopulated at http://linode-4.rebbi.is:1085/cgi-bin/WebObjects/wotaskd.woa/wa/woconfig"
