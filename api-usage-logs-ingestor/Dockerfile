FROM jbangdev/jbang-action:0.101.0 as jbang-build

RUN mkdir /app
WORKDIR /app
COPY Ingest.java ./

RUN jbang export portable -Dquarkus.profile=prod -O ingest.jar Ingest.java


FROM eclipse-temurin:11-alpine

RUN mkdir /app
WORKDIR /app

COPY --from=jbang-build /app/ingest.jar ./
COPY --from=jbang-build /app/lib ./lib

ENV QUARKUS_BANNER_ENABLED="false" \
  QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://127.0.0.1:5432/opertusmundi" \
  QUARKUS_DATASOURCE_USERNAME="opertusmundi" \
  QUARKUS_DATASOURCE_PASSWORD=""

ENTRYPOINT ["java", "-jar", "ingest.jar"]
