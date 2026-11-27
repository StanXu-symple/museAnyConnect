# syntax=docker/dockerfile:1.7

# 第一阶段：在宿主机原生架构上静态编译 Go 二进制（支持 amd64/arm64 交叉编译）
FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS builder

WORKDIR /build

RUN apk add --no-cache ca-certificates git

COPY go.mod ./
COPY *.go ./

ARG TARGETOS=linux
ARG TARGETARCH

# 补齐依赖并静态编译无 CGO 的极简二进制文件
RUN go mod tidy \
    && CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -trimpath -ldflags="-s -w" -o /build/tg-user-bridge .

# 第二阶段：极简运行时镜像（约 15~20MB）
FROM alpine:3.21

WORKDIR /app

RUN apk add --no-cache ca-certificates curl \
    && addgroup -S muse \
    && adduser -S -G muse -h /app -s /sbin/nologin muse

COPY --from=builder --chown=muse:muse /build/tg-user-bridge /app/tg-user-bridge

ENV TG_BRIDGE_PORT=8081 \
    TG_BRIDGE_TOKEN=""

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${TG_BRIDGE_PORT:-8081}/health" >/dev/null || exit 1

USER muse

ENTRYPOINT ["/app/tg-user-bridge"]
