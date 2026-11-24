FROM eclipse-temurin:21-jdk AS build

ARG FT_API_ORIGIN
ENV FT_API_ORIGIN=${FT_API_ORIGIN}

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 libatomic1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY . .

RUN --mount=type=cache,id=finance-tracker-gradle,target=/root/.gradle,sharing=locked \
    sed -i '/^toolchainVendor=/d' compose/gradle/gradle-daemon-jvm.properties \
    && cd compose \
    && ./build-wasm-web.sh --no-configuration-cache

FROM nginx:1.27-alpine

COPY compose/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/compose/webApp/src/webMain/resources/ /usr/share/nginx/html/
COPY --from=build /workspace/compose/webApp/build/processedResources/wasmJs/main/ /usr/share/nginx/html/
COPY --from=build /workspace/compose/webApp/build/dist/wasmJs/productionExecutable/ /usr/share/nginx/html/

EXPOSE 80
