FROM alpine:3.19

RUN apk add --no-cache dnsmasq

COPY dnsmasq.conf /etc/dnsmasq.conf

EXPOSE 53/udp 53/tcp

# -k = keep in foreground (so Docker can manage it).
# --no-resolv = do not use /etc/resolv.conf for upstream.
CMD ["dnsmasq", "-k", "--no-resolv", "--conf-file=/etc/dnsmasq.conf"]
