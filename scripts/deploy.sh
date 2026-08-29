#!/usr/bin/env bash
# Deploy wotaskd and JavaMonitor to hz1.rebbi.is.
#
# Steps:
#   1. Local: install fresh sjip-core
#   2. Local: package wotaskd and JavaMonitor (pinned to /opt/jdk-26)
#   3. Server: move the existing .woa bundles aside (pruning older backups)
#   4. scp: upload the new bundles
#   5. Server: restart the services — JavaMonitor first (harmless),
#      wotaskd second (apps keep running; adaptors keep serving their
#      last-known config and re-poll within seconds)
set -euo pipefail

SERVER="root@hz1.rebbi.is"
REMOTE_APPS_DIR="/opt/webobjects/apps"
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
	WOA_LOCAL="${REPO_ROOT}/${APP}/target/${APP}.woa"
	if [ ! -d "${WOA_LOCAL}" ]; then
		echo "Build did not produce ${WOA_LOCAL}" >&2
		exit 1
	fi
done

echo "==> [3/5] Moving existing remote bundles aside (pruning older backups)"
for APP in wotaskd JavaMonitor; do
	LIVE="${REMOTE_APPS_DIR}/${APP}.woa"
	ssh "${SERVER}" "if [ -e '${LIVE}' ]; then mv '${LIVE}' '${LIVE}.prev-${STAMP}'; fi
		ls -d '${REMOTE_APPS_DIR}/${APP}.woa.prev-'* 2>/dev/null | sort | head -n -1 | xargs -r rm -rf"
done

echo "==> [4/5] Uploading new bundles"
for APP in wotaskd JavaMonitor; do
	scp -q -r "${REPO_ROOT}/${APP}/target/${APP}.woa" "${SERVER}:${REMOTE_APPS_DIR}/${APP}.woa"
	ssh "${SERVER}" "chmod -R 777 '${REMOTE_APPS_DIR}/${APP}.woa'"
done

echo "==> [5/5] Restarting services (JavaMonitor, then wotaskd)"
ssh "${SERVER}" "service javamonitor stop && service javamonitor start"
ssh "${SERVER}" "service wotaskd stop && service wotaskd start"

echo "==> Done. Previous bundles preserved as *.woa.prev-${STAMP} on the server."
