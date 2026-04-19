#!/bin/sh
# Maven Wrapper bootstrap script
# Usage: ./mvnw [maven arguments]

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(dirname "$0")}"
WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

# Download wrapper JAR if not present
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Maven Wrapper..."
    curl -L "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar" \
        -o "$WRAPPER_JAR" 2>/dev/null
fi

# Download Maven if not present
MAVEN_HOME="${MAVEN_PROJECTBASEDIR}/.mvn/maven"
if [ ! -d "$MAVEN_HOME" ]; then
    mkdir -p "$MAVEN_PROJECTBASEDIR/.mvn/maven"
    MAVEN_DIST="apache-maven-3.9.6-bin.zip"
    if [ ! -f "$MAVEN_PROJECTBASEDIR/.mvn/$MAVEN_DIST" ]; then
        echo "Downloading Maven..."
        curl -L "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/$MAVEN_DIST" \
            -o "$MAVEN_PROJECTBASEDIR/.mvn/$MAVEN_DIST" 2>/dev/null
    fi
    unzip -q "$MAVEN_PROJECTBASEDIR/.mvn/$MAVEN_DIST" -d "$MAVEN_PROJECTBASEDIR/.mvn/"
fi

exec "$MAVEN_PROJECTBASEDIR/.mvn/maven/apache-maven-3.9.6/bin/mvn" "$@"
