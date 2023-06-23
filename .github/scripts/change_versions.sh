#!/bin/bash

# Expects the following environment variables to be set:
#   $NEW_VERSION                 (Example: "1.2.0")

# There are nicer ways to do this, but this one does not require fully resolving the Maven project.
# As such, it functions even though the SNAPSHOT referenced is not available.
DETECTED_VERSION=$(mvn -q -Dexec.executable="echo" -Dexec.args='${project.version}' --non-recursive org.codehaus.mojo:exec-maven-plugin:3.1.0:exec)
echo "Detected version: $DETECTED_VERSION"
echo "     New version: $NEW_VERSION"
find . -name pom.xml | xargs sed -i "s/$DETECTED_VERSION/$NEW_VERSION/g"
find . -name pom.xml | xargs git add
git commit -m "chore: switch to version $NEW_VERSION"
