# Fail-closed stand-in until kaze ships the Quarkus sms service (#107).
# Listens on 8080.
FROM python:3.12-alpine

COPY server.py /server.py

USER nobody
EXPOSE 8080
CMD ["python3", "/server.py"]
