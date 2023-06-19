#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
# SPDX-License-Identifier: EPL-2.0
#
# Author: Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)

#####################
# Script init
#####################
set -eu

# execute script with bash if loaded with other shell interpreter
if [ -z "${BASH_VERSINFO:-}" ]; then /usr/bin/env bash "$0" "$@"; exit; fi

set -o pipefail

# configure stack trace reporting
trap 'rc=$?; echo >&2 "$(date +%H:%M:%S) Error - exited with status $rc in [$BASH_SOURCE] at line $LINENO:"; cat -n $BASH_SOURCE | tail -n+$((LINENO - 3)) | head -n7' ERR

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"


#####################
# Main
#####################

cd $(dirname $0)/..

echo
echo "###################################################"
echo "# Determining GIT branch......                    #"
echo "###################################################"
GIT_BRANCH=$(git branch --show-current)
echo "  -> GIT Branch: $GIT_BRANCH"; echo

if ! hash mvn 2>/dev/null; then
   echo
   echo "###################################################"
   echo "# Determinig latest Maven version...              #"
   echo "###################################################"
   #MAVEN_VERSION=$(curl -sSf https://repo1.maven.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml | grep -oP '(?<=latest>).*(?=</latest)')
   MAVEN_VERSION=$(curl -sSf https://dlcdn.apache.org/maven/maven-3/ | grep -oP '(?<=>)[0-9.]+(?=/</a)' | tail -1)
   echo "  -> Latest Maven Version: ${MAVEN_VERSION}"
   if [[ ! -e $HOME/.m2/bin/apache-maven-$MAVEN_VERSION ]]; then
      echo
      echo "###################################################"
      echo "# Installing Maven version $MAVEN_VERSION...               #"
      echo "###################################################"
      mkdir -p $HOME/.m2/bin/
      #maven_download_url="https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      maven_download_url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      echo "Downloading [$maven_download_url]..."
      curl -fsSL $maven_download_url | tar zxv -C $HOME/.m2/bin/
   fi
   export M2_HOME=$HOME/.m2/bin/apache-maven-$MAVEN_VERSION
   export PATH=$M2_HOME/bin:$PATH
fi


echo
echo "###################################################"
echo "# Configuring MAVEN_OPTS...                       #"
echo "###################################################"
MAVEN_OPTS="${MAVEN_OPTS:-}"
MAVEN_OPTS="$MAVEN_OPTS -XX:+TieredCompilation -XX:TieredStopAtLevel=1" # https://zeroturnaround.com/rebellabs/your-maven-build-is-slow-speed-it-up/
MAVEN_OPTS="$MAVEN_OPTS -Djava.security.egd=file:/dev/./urandom" # https://stackoverflow.com/questions/58991966/what-java-security-egd-option-is-for/59097932#59097932
MAVEN_OPTS="$MAVEN_OPTS -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS" # https://stackoverflow.com/questions/5120470/how-to-time-the-different-stages-of-maven-execution/49494561#49494561
export MAVEN_OPTS="$MAVEN_OPTS -Xmx1024m -Djava.awt.headless=true -Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.2"
echo "  -> MAVEN_OPTS: $MAVEN_OPTS"

MAVEN_CLI_OPTS="-e -U --batch-mode --show-version --no-transfer-progress -s .ci/maven-settings.xml -t .ci/maven-toolchains.xml"


echo
echo "###################################################"
echo "# Updating EEA Files...                           #"
echo "###################################################"
mvn $MAVEN_CLI_OPTS "$@" \
   help:active-profiles compile -Deea-generator.action=generate \
      | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms
