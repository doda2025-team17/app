# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-25 AS build

# Set working directory
WORKDIR /app

# Accept GitHub token from build args (passed by GitHub Actions)
ARG GITHUB_TOKEN

# Configure Maven to use GitHub Packages for nl.tudelft.doda:lib-version
RUN mkdir -p /root/.m2 && \
    printf '<settings>\n\
  <servers>\n\
    <server>\n\
      <id>github</id>\n\
      <username>x-access-token</username>\n\
      <password>%s</password>\n\
    </server>\n\
  </servers>\n\
</settings>' "$GITHUB_TOKEN" > /root/.m2/settings.xml

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

# Copy the JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Set environment variables
ENV MODEL_HOST="http://model-service:8081"
ENV APP_SERVER_PORT=8080

# Expose port
EXPOSE ${APP_SERVER_PORT}

# Just run the JAR (fast startup!)
CMD ["java", "-jar", "app.jar", "--server.port=${APP_SERVER_PORT}"]
