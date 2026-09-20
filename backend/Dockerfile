# SeaStella backend - one image, built from source.
#
# Java 17: the version every test has passed on. The pom targets 25 after the
# upgrade commit, but that combination has not been run yet, so the build pins
# 17 explicitly until it has (see docs/09-deployment.md).

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Dependencies first, so a code change does not re-download them.
COPY pom.xml ./
COPY platform-core/pom.xml platform-core/
COPY identity-access/pom.xml identity-access/
COPY fleet/pom.xml fleet/
COPY maintenance/pom.xml maintenance/
COPY service-request/pom.xml service-request/
COPY troubleshooting/pom.xml troubleshooting/
COPY invoice/pom.xml invoice/
COPY masterdata-import/pom.xml masterdata-import/
COPY activity-feed/pom.xml activity-feed/
COPY notification/pom.xml notification/
COPY reporting/pom.xml reporting/
COPY app/pom.xml app/
RUN mvn -B -q -Djava.version=17 dependency:go-offline -DexcludeReactor=true || true
COPY . .
RUN mvn -B -Djava.version=17 -DskipTests package -pl app -am

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 seastella
COPY --from=build /src/app/target/seastella.jar /app/seastella.jar
USER seastella
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/seastella.jar"]
