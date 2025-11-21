# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-25 AS build

# Copy only dependency definitions first (for caching)
COPY pom.xml .

# Download dependencies (this layer will be cached)
RUN mvn dependency:go-offline -B

# Now copy source code
COPY src/ ./src/

# Build the JAR once during image build
RUN mvn clean package -DskipTests

# Stage 2: Create the final, smaller image
FROM eclipse-temurin:25-jre-jammy

# Set working directory
WORKDIR /app

# Copy the JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Set environment variables
ENV MODEL_HOST="http://model-service:8081"
ENV SERVER_PORT=8080

# Expose port
EXPOSE ${SERVER_PORT}

# Just run the JAR (fast startup!)
CMD ["java", "-jar", "app.jar", "--server.port=${SERVER_PORT}"]