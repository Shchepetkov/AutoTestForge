#!/bin/sh
set -eu

repository=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
port=8080
no_build=false
check=false
roots=$repository
custom_roots=false
maven_home=${MAVEN_HOME:-${M2_HOME:-}}

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
    printf '%s\n' 'Usage: sh launch-web.sh [--project-root DIRECTORY] [--java-home JDK] [--maven-home MAVEN] [--port PORT] [--no-build] [--check]'
}
while [ "$#" -gt 0 ]; do
    case "$1" in
        --project-root|--java-home|--maven-home|--port)
            [ "$#" -ge 2 ] || fail "Missing value for $1"
            case "$1" in
                --project-root)
                    directory=$(CDPATH= cd -- "$2" && pwd) || fail "Project root is not a directory: $2"
                    case "$directory" in *,*) fail 'Project roots containing commas are not supported.';; esac
                    roots="$roots,$directory"
                    custom_roots=true ;;
                --java-home) JAVA_HOME=$2; export JAVA_HOME ;;
                --maven-home) maven_home=$2 ;;
                --port) port=$2 ;;
            esac
            shift 2 ;;
        --no-build) no_build=true; shift ;;
        --check) check=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) usage; fail "Unknown option: $1" ;;
    esac
done
case "$port" in ''|*[!0-9]*) fail 'Port must be a number from 1 to 65535.';; esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || fail 'Port must be a number from 1 to 65535.'

if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/java" ] && [ -x "$JAVA_HOME/bin/javac" ] || fail 'JAVA_HOME must point to a full JDK 17+ containing bin/java and bin/javac.'
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi
java=$(command -v java) || fail 'Install JDK 17+ and set JAVA_HOME or pass --java-home.'
command -v javac >/dev/null 2>&1 || fail 'A full JDK 17+ (including javac) is required.'
java_version=$("$java" -version 2>&1) || fail 'Java could not start.'
java_major=$(printf '%s\n' "$java_version" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
[ -n "$java_major" ] || fail "Cannot determine Java version: $java_version"
[ "$java_major" -ge 17 ] || fail "Java $java_major is too old. Install JDK 17+ and set JAVA_HOME."

if [ -n "$maven_home" ]; then
    [ -x "$maven_home/bin/mvn" ] || fail 'Maven home must contain an executable bin/mvn.'
    PATH="$maven_home/bin:$PATH"
    export PATH
fi
maven=$(command -v mvn || true)
if [ -n "$maven" ]; then
    maven_version=$("$maven" -version 2>&1) || fail 'Maven could not start. Check JAVA_HOME.'
    maven_numbers=$(printf '%s\n' "$maven_version" | sed -n 's/.*Apache Maven \([0-9][0-9]*\)\.\([0-9][0-9]*\).*/\1 \2/p' | head -n 1)
    [ -n "$maven_numbers" ] || fail "Cannot determine Maven version: $maven_version"
    set -- $maven_numbers
    [ "$1" -gt 3 ] || { [ "$1" -eq 3 ] && [ "$2" -ge 9 ]; } || fail 'Maven 3.9+ is required.'
elif [ "$no_build" = false ]; then
    fail 'Install Maven 3.9+, add bin to PATH, or pass --maven-home. Company mirror/proxy settings belong in ~/.m2/settings.xml.'
else
    printf '%s\n' 'Maven is unavailable. The packaged site can run, but Maven validation requires Maven or a working project wrapper.' >&2
fi
if [ "$custom_roots" = false ] && [ -n "${ATF_ALLOWED_ROOTS:-}" ]; then roots=$ATF_ALLOWED_ROOTS; fi
printf 'Java: %s\nMaven: %s\nAllowed project roots: %s\n' "$java" "${maven:-unavailable}" "$roots"
if [ "$check" = true ]; then printf '%s\n' 'Prerequisites OK. No build or server was started.'; exit 0; fi

cd "$repository"
if [ "$no_build" = false ]; then
    "$maven" -B -pl atf-web -am package -DskipTests || fail 'Build failed. Check Maven output and company mirror/proxy settings in ~/.m2/settings.xml.'
fi
jar="$repository/atf-web/target/atf-web-0.1.0.jar"
[ -f "$jar" ] || fail 'Web JAR is missing. Run this script once without --no-build.'
printf 'Open http://localhost:%s in your browser. Keep this terminal open; Ctrl+C stops the site.\n' "$port"
exec "$java" -jar "$jar" --server.address=127.0.0.1 "--server.port=$port" "--atf.workspace.allowed-roots=$roots"
