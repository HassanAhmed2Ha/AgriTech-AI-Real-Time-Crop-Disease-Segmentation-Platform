# Start from a lightweight Python 3.12 image
FROM python:3.12-slim

# Set up a working directory
WORKDIR /app

# Install system dependencies required for OpenCV
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Copy the requirements file and install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy all the backend files into the container
COPY . .

# Set up environment variables
ENV HOST=0.0.0.0
ENV PORT=7860

# Expose the port Hugging Face Spaces expects
EXPOSE 7860

# Start the FastAPI application
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860"]