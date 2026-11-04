FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN cd compose && ./gradlew :backend:installDist --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/compose/backend/build/install/backend/ /app/
ENV FT_HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
USER app
ENTRYPOINT ["/app/bin/backend"]
