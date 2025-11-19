# Use Maven image with Java 25
FROM maven:3.9-eclipse-temurin-25

# Set working directory
WORKDIR /app

# Copy only dependency definitions first (for caching)
COPY pom.xml .

# Download dependencies (this layer will be cached)
RUN mvn dependency:go-offline -B

# Now copy source code
COPY src/ ./src/

# Build the JAR once during image build
RUN mvn clean package -DskipTests

# Set environment variables
ENV MODEL_HOST="http://model-service:8081"
ENV SERVER_PORT=8080

# Expose port
EXPOSE ${SERVER_PORT}

# Just run the JAR (fast startup!)
CMD ["sh", "-c", "java -jar target/*.jar --server.port=$SERVER_PORT"]