FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline -B

COPY src ./src
RUN mvn -q clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="sql-mcp" \
      org.opencontainers.image.description="Read-only MCP server for Microsoft SQL Server" \
      org.opencontainers.image.version="1.0.0"

# Non-root user for security
RUN addgroup -S mcp && adduser -S mcp -G mcp
USER mcp

WORKDIR /app
COPY --from=build /build/target/sql-mcp-1.0.0.jar app.jar

VOLUME ["/app/config"]

# HTTP transport port (only relevant when MCP_TRANSPORT=http)
EXPOSE 8080

# MCP_TRANSPORT=stdio  → reads JSON-RPC from stdin (default, local dev)
# MCP_TRANSPORT=http   → listens on MCP_PORT (default 8080), for server/Docker
ENV MCP_TRANSPORT=stdio
ENV MCP_PORT=8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
