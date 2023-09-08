#!/bin/bash

# Expects the following environment variables to be set:
#   $NEW_VERSION                 (Example: "1.2.0")

# This will fail the Maven build if the version is not available.
# Thankfully, this is not the case (yet) in this project.
# If/when it happens, this needs to be replaced by a manually provided version,
# as scanning the text of the POM would be unreliable.
DETECTED_VERSION=$(mvn -q -Dexec.executable="echo" -Dexec.args='${project.version}' --non-recursive org.codehaus.mojo:exec-maven-plugin:3.1.0:exec)
echo "Detected version: $DETECTED_VERSION"
echo "     New version: $NEW_VERSION"
find . -name pom.xml | xargs sed -i "s/$DETECTED_VERSION/$NEW_VERSION/g"
find . -name pom.xml | xargs git add
git commit -m "build: switch to version $NEW_VERSION"
