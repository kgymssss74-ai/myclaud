# Use an official Python runtime as a parent image
FROM python:3.10-slim

# Set the working directory in the container
WORKDIR /app

# Copy the requirements file into the container
COPY requirements.txt .

# Install any needed packages specified in requirements.txt
# --no-cache-dir to keep the image size small
RUN pip install --no-cache-dir -r requirements.txt

# Copy the current directory contents into the container at /app
# (.dockerignore will prevent unnecessary files from being copied)
COPY . .

# Run the web service on container startup. 
# Cloud Run injects the PORT environment variable.
# We use uvicorn to serve the FastAPI app.
CMD exec uvicorn server:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1
