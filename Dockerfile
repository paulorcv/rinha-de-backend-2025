# Use a imagem oficial do OpenJDK como base
FROM eclipse-temurin:17-jdk-jammy as builder
WORKDIR /app
# Copie o projeto para dentro do container
COPY . .
# Crie o fat jar usando Maven
RUN ./mvnw clean package -DskipTests

# Segunda stage, para imagem final
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# Copie o jar do stage anterior
COPY --from=builder /app/target/*.jar app.jar

ENV JAVA_OPTS="\
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -Xms64m \
    -Xmx128m \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8"


EXPOSE 9999
ENTRYPOINT ["java", "-jar", "app.jar"]
