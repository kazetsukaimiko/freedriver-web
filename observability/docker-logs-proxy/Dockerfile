FROM alpine:3.21
RUN apk add --no-cache python3
COPY docker_logs_proxy.py /usr/local/bin/docker_logs_proxy.py
RUN chmod 0755 /usr/local/bin/docker_logs_proxy.py
ENV PYTHONDONTWRITEBYTECODE=1
ENTRYPOINT ["python3", "-u", "/usr/local/bin/docker_logs_proxy.py"]
