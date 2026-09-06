FROM node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS codex-runtime

WORKDIR /opt/codex
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

FROM eclipse-temurin:21-jdk@sha256:85f00967bcc624fc19fa9c2cf124ea426a5363898e267141726f31f358c2e14b AS java-runtime

# Keep Bubblewrap on the Debian Bookworm line. The mutable 3.13-slim tag moved
# to Trixie, whose Bubblewrap 0.12 cannot create the Codex sandbox under the
# runner's intentionally restricted container capabilities.
FROM python:3.13-slim-bookworm@sha256:c45a22ea000adfd9cda29364bbe7edd23001ce5cc2ad15857cfbf7766943b9ca AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends bubblewrap=0.8.0-2+deb12u1 ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 synvo-runner \
    && useradd --uid 10001 --gid synvo-runner --create-home synvo-runner \
    && mkdir -p /var/lib/synvo-codex /var/lib/synvo-mcp-fixture \
    && chown synvo-runner:synvo-runner /var/lib/synvo-codex /var/lib/synvo-mcp-fixture \
    && chmod 0700 /var/lib/synvo-codex /var/lib/synvo-mcp-fixture

COPY --from=codex-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=codex-runtime /usr/local/lib/node_modules /usr/local/lib/node_modules
# Cross-stage COPY dereferences launcher symlinks; recreate them so npm's
# relative imports resolve from its package directory.
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && npm --version \
    && npx --version
COPY --from=codex-runtime /opt/codex /opt/codex
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk

WORKDIR /app
COPY synvo_runner synvo_runner
COPY protocol protocol
COPY fixtures fixtures

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SYNVO_CODEX_COMMAND=/opt/codex/node_modules/.bin/codex \
    SYNVO_CODEX_HOME=/var/lib/synvo-codex \
    SYNVO_CODEX_RUNTIME_VERSION=0.148.0 \
    SYNVO_CODEX_RUNNER_HOST=0.0.0.0 \
    SYNVO_CODEX_RUNNER_PORT=8090 \
    JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin

USER synvo-runner
EXPOSE 8090

HEALTHCHECK --interval=5s --timeout=3s --retries=10 --start-period=5s \
  CMD ["python3", "-c", "import json,urllib.request; assert json.load(urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=2))['state'] in ('ready','disabled')"]

ENTRYPOINT ["python3", "-m", "synvo_runner.main"]
