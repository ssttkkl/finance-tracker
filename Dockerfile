FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,id=finance-tracker-gradle,target=/root/.gradle,sharing=locked \
    sed -i '/^toolchainVendor=/d' compose/gradle/gradle-daemon-jvm.properties \
    && cd compose && ./gradlew :backend:installDist --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 app && mkdir /data && chown app:app /data
WORKDIR /app
COPY --from=build /workspace/compose/backend/build/install/backend/ /app/
ENV FT_HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
USER app
ENTRYPOINT ["/app/bin/backend"]
