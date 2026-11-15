FROM python:3.12-alpine
WORKDIR /app
RUN pip install --no-cache-dir paho-mqtt==2.1.0
COPY responder.py /app/responder.py
EXPOSE 8080
CMD ["python3", "-u", "/app/responder.py"]
