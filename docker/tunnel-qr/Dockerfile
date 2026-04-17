FROM python:3.14-alpine

RUN pip install --no-cache-dir "qrcode[pil]==8.0"

WORKDIR /app
COPY serve.py .

EXPOSE 19999

CMD ["python", "serve.py"]
