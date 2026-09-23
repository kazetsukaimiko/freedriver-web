# Fail-closed stand-in until kaze ships the Quarkus sms binary (#107).
# Listens on 8080. Does not send SMS and has no AWS credentials.
FROM python:3.12-alpine

COPY server.py /server.py

USER nobody
EXPOSE 8080
CMD ["python3", "/server.py"]
