# ─────────────────────────────────────────────────────────────────────────────
# Dockerfile — Packages WireMock standalone with ApiNexus stub mappings.
#
# Base image: wiremock/wiremock (official Docker image from WireMock project)
# It ships the WireMock standalone JAR and exposes port 8080 by default.
#
# BUILD:
#   docker build -t apinexus-wiremock .
#
# RUN (locally):
#   docker run -p 8080:8080 apinexus-wiremock
#
# The server will be accessible at http://localhost:8080
# Admin API at                         http://localhost:8080/__admin
# ─────────────────────────────────────────────────────────────────────────────

FROM wiremock/wiremock:3.5.4-1

# ── Copy stub mapping files ────────────────────────────────────────────────
# WireMock loads stubs from /home/wiremock/mappings/ on startup.
# Each .json file in that directory becomes a pre-registered stub.
COPY wiremock/mappings/ /home/wiremock/mappings/

# ── Copy response body files ───────────────────────────────────────────────
# When a stub references "bodyFileName": "users-response.json", WireMock
# looks for it in /home/wiremock/__files/.
COPY wiremock/__files/ /home/wiremock/__files/

# ── WireMock startup flags ─────────────────────────────────────────────────
# --port 8080            : listen on 8080 (Fly.io / Render expect this port)
# --verbose              : log every request — useful during initial setup
# --global-response-templating : enables Handlebars templating in stub bodies
#   (e.g. {{request.body}} to echo back request content)
CMD ["--port", "8080", "--verbose", "--global-response-templating"]
