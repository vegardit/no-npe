#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors
# SPDX-FileContributor: Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
# SPDX-License-Identifier: EPL-2.0
# SPDX-ArtifactOfProjectHomePage: https://github.com/vegardit/no-npe

#####################
# Script init
#####################
set -eu

# execute script with bash if loaded with other shell interpreter
if [ -z "${BASH_VERSINFO:-}" ]; then /usr/bin/env bash "$0" "$@"; exit; fi

set -o pipefail

# configure stack trace reporting
# shellcheck disable=SC2154 # (warning): rc is referenced but not assigned.
trap 'rc=$?; echo >&2 "$(date +%H:%M:%S) Error - exited with status $rc in [$BASH_SOURCE] at line $LINENO:"; cat -n $BASH_SOURCE | tail -n+$((LINENO - 3)) | head -n7' ERR

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"


#####################
# Main
#####################

cd "$SCRIPT_DIR/.."

echo
echo "###################################################"
echo "# Determining GIT branch......                    #"
echo "###################################################"
GIT_BRANCH=$(git branch --show-current)
echo "  -> GIT Branch: $GIT_BRANCH"; echo


echo
echo "###################################################"
echo "# Configuring MAVEN_OPTS...                       #"
echo "###################################################"
MAVEN_OPTS="${MAVEN_OPTS:-}"
MAVEN_OPTS="$MAVEN_OPTS -Djava.security.egd=file:/dev/./urandom" # https://stackoverflow.com/questions/58991966/what-java-security-egd-option-is-for/59097932#59097932
MAVEN_OPTS="$MAVEN_OPTS -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS" # https://stackoverflow.com/questions/5120470/how-to-time-the-different-stages-of-maven-execution/49494561#49494561
export MAVEN_OPTS="$MAVEN_OPTS -Xmx1024m -Djava.awt.headless=true -Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.2"
echo "  -> MAVEN_OPTS: $MAVEN_OPTS"

MAVEN_CLI_OPTS="-e -U --batch-mode --show-version --no-transfer-progress"


echo
echo "###################################################"
echo "# Updating EEA Files...                           #"
echo "###################################################"
chmod u+x ./mvnw
# shellcheck disable=SC2086 # (info): Double quote to prevent globbing and word splitting
./mvnw $MAVEN_CLI_OPTS "$@" \
   help:active-profiles compile -Deea-generator.action=generate \
      | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms
