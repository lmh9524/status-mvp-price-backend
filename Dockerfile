FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV PORT=3003
EXPOSE 3003
COPY --from=build /app/target/status-mvp-price-backend-0.0.1.jar /app/app.jar
CMD ["sh","-lc","java -Dserver.port=${PORT} -jar /app/app.jar"]


