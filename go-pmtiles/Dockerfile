# go-pmtiles + in-container reload watcher.
#
# The upstream image is distroless (single /go-pmtiles binary, no shell), so the
# binary is copied onto an alpine base where reload-wrapper.sh can supervise it:
# the wrapper restarts the serve process whenever region_merger.py writes
# /map-data/.reload-trigger. See reload-wrapper.sh for why this exists.
FROM protomaps/go-pmtiles:v1.31.2 AS upstream

FROM alpine:3.24
COPY --from=upstream /go-pmtiles /usr/local/bin/go-pmtiles
COPY reload-wrapper.sh /usr/local/bin/reload-wrapper.sh
RUN chmod +x /usr/local/bin/reload-wrapper.sh

ENTRYPOINT ["/usr/local/bin/reload-wrapper.sh"]
CMD ["serve", "/map-data", "--cache-size=128"]
