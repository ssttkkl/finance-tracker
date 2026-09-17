FROM mcr.microsoft.com/devcontainers/base:bookworm

ARG CODEX_VERSION=0.154.0

RUN curl -fsSL https://chatgpt.com/codex/install.sh \
  | CODEX_NON_INTERACTIVE=true \
    CODEX_INSTALLER_USE_RELEASES_OPENAI_COM=true \
    CODEX_INSTALL_DIR=/usr/local/bin \
    CODEX_HOME=/usr/local/share/codex \
    sh -s -- --release "${CODEX_VERSION}"
