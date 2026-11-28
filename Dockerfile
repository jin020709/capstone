# =====================================================
# GlobalPrice 배포용 이미지 (Railway 등)
#  - 저장소 맨 위(capstone)에 둔다: 빌드가 ../GlobalPrice_frontend 의 화면 파일도 쓰기 때문
#  - 1단계: JDK 25 로 bootJar 빌드 (테스트는 건너뜀)
#  - 2단계: JRE 25 로 실행. 포트는 Railway 가 주는 PORT, 없으면 8080
#  - API 키 · DB 비밀번호는 이미지에 넣지 않고 Railway Variables(환경 변수)로 받는다
# =====================================================

# ---- 1단계: 빌드 ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
WORKDIR /app/globalprice-backend
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

# ---- 2단계: 실행 ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/globalprice-backend/build/libs/*-SNAPSHOT.jar app.jar
ENV TZ=Asia/Seoul
EXPOSE 8080
CMD ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar app.jar"]
