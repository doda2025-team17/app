# Use Maven image with Java 25
FROM maven:3.9-eclipse-temurin-25

# Set working directory
WORKDIR /app

# Copy source code and pom
COPY src/ ./src/
COPY pom.xml .

# Expose port
EXPOSE 8080

# Set environment variable for model host
ENV MODEL_HOST="http://model-service:8081"

# Run the application
CMD ["mvn", "spring-boot:run"]