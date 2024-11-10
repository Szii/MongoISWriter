FROM openjdk:18-jdk-alpine

WORKDIR /app

COPY pom.xml ./

RUN apk add --no-cache maven && mvn dependency:resolve

COPY src /app/src

RUN mvn package -DskipTests

# EXPOSE <PORT>

CMD ["java", "-jar", "target/app.jar"]
