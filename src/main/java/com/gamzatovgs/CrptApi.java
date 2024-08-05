package com.gamzatovgs;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Потокобезопасный (thread-safe) класс-клиент для работы с API Честного знака,
 * поддерживает ограничение на количество запросов.
 */
public class CrptApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrptApi.class);
    private static final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final int NUMBER_OF_REQUESTS = 100;

    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final Semaphore semaphore;
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;

    /**
     * Конструктор класса CrptApi.
     *
     * @param timeUnit      указывает промежуток времени – секунда, минута и пр.
     * @param requestLimit  положительное значение, которое определяет максимальное количество запросов в этом промежутке времени.
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        LOGGER.debug("Временной промежуток timeUnite = " + timeUnit.name());

        this.requestLimit = requestLimit;
        LOGGER.debug("Ограничение на количество запросов в этом временном промежутке requestLimit = " + requestLimit);

        this.semaphore = new Semaphore(requestLimit);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.httpClient = HttpClient.newHttpClient();

        // Запуск планировщика задач, в котором метод release() «освобождает» выданное ранее разрешение на доступ
        // к ресурсу сервера и возвращает его в счетчик семафора, согласно заданному временному промежутку timeUnit
        // и ограничению на количество запросов в этом временном промежутке requestLimit.
        scheduler.scheduleAtFixedRate(() -> {
            LOGGER.info("Освобождение " + (requestLimit - semaphore.availablePermits()) + " ранее выданных разрешений на доступ к ресурсу сервера и возвращение их в счетчик семафора");
            semaphore.release(requestLimit - semaphore.availablePermits());
        }, 0, 1, timeUnit);
    }

    /**
     * Метод для создания документа для ввода в оборот товара, произведенного в РФ.
     *
     * @param document               документ, который передается в метод в виде Java объекта.
     * @param signature              подпись, которая передается в метод в виде строки.
     * @throws InterruptedException  выбрасывает исключение для обработки, если поток прерван во время ожидания разрешения.
     * @throws IOException           выбрасывает исключение для обработки, если оно возникло во время отправки HTTP-запроса.
     */
    public HttpResponse<String> createDocument(Document document, String signature) throws InterruptedException, IOException {
        // Запрашиваем разрешение на доступ к ресурсу у семафора.
        // Если счетчик > 0, разрешение предоставляется, а счетчик уменьшается на 1.
        LOGGER.info("Запрос разрешения на доступ к ресурсу сервера через API у семафора, доступно разрешений - " + semaphore.availablePermits());
        semaphore.acquire();

        // Преобразуем объект Document в JSON.
        Gson gson = new Gson();
        String json = gson.toJson(document);

        // Отправка запроса на сервер.
        LOGGER.info("Построение HTTP-запроса");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        LOGGER.info("Отправка HTTP-запроса на сервер и получение ответа");
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Метод для остановки планировщика задач.
     */
    private void shutdownScheduler() {
        LOGGER.info("Остановка планировщика задач");
        scheduler.shutdown();
    }

    public static void main(String[] args) {
        // Время запуска приложения
        long startTime = System.currentTimeMillis();

        CrptApi crptApi = new CrptApi(TimeUnit.SECONDS, 10);

        // Читаем JSON из файла и преобразуем в строку.
        String json = "";
        try {
            byte[] bytes = Files.readAllBytes(Paths.get("src/main/resources/document.json"));
            json = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Преобразуем прочтенный JSON из строки в Java объект.
        Gson gson = new Gson();
        CrptApi.Document document = gson.fromJson(json, CrptApi.Document.class);

        String signature = "SIGNATURE";

        LOGGER.info("Отправка " + NUMBER_OF_REQUESTS + " последовательных HTTP-запросов на сервер в цикле");
        for (int i = 0; i < NUMBER_OF_REQUESTS; ++i) {
            try {
                // Создание документа и получение ответа от сервера.
                HttpResponse<String> response = crptApi.createDocument(document, signature);

                // Проверяем статус ответа.
                LOGGER.info("Проверка статуса ответа");
                int statusCode = response.statusCode();
                if (statusCode == 200) {
                    System.out.println("\nТело ответа:\n" + response.body() + "\n");
                } else {
                    System.out.println("\nОшибка: " + statusCode + "\n");
                }
            } catch (IOException | InterruptedException e) {
                LOGGER.error(e.getMessage());
            }
        }

        // Остановка планировщика задач.
        crptApi.shutdownScheduler();

        // Время остановки приложения
        long stopTime = System.currentTimeMillis();

        LOGGER.info("Выполнение " + NUMBER_OF_REQUESTS + " запросов заняло " + (stopTime - startTime) + " миллисекунд");
    }

    /**
     * Внутренний класс, описывающий объект документа.
     */
    static class Document {
        private Description description;
        private String docId;
        private String docStatus;
        private String docType;
        private boolean importRequest;
        private String ownerInn;
        private String participantInn;
        private String producerInn;
        private String productionDate;
        private String productionType;
        private List<Product> products;
        private String regDate;
        private String regNumber;

        public Description getDescription() {
            return description;
        }

        public void setDescription(Description description) {
            this.description = description;
        }

        public String getDocId() {
            return docId;
        }

        public void setDocId(String docId) {
            this.docId = docId;
        }

        public String getDocStatus() {
            return docStatus;
        }

        public void setDocStatus(String docStatus) {
            this.docStatus = docStatus;
        }

        public String getDocType() {
            return docType;
        }

        public void setDocType(String docType) {
            this.docType = docType;
        }

        public boolean isImportRequest() {
            return importRequest;
        }

        public void setImportRequest(boolean importRequest) {
            this.importRequest = importRequest;
        }

        public String getOwnerInn() {
            return ownerInn;
        }

        public void setOwnerInn(String ownerInn) {
            this.ownerInn = ownerInn;
        }

        public String getParticipantInn() {
            return participantInn;
        }

        public void setParticipantInn(String participantInn) {
            this.participantInn = participantInn;
        }

        public String getProducerInn() {
            return producerInn;
        }

        public void setProducerInn(String producerInn) {
            this.producerInn = producerInn;
        }

        public String getProductionDate() {
            return productionDate;
        }

        public void setProductionDate(String productionDate) {
            this.productionDate = productionDate;
        }

        public String getProductionType() {
            return productionType;
        }

        public void setProductionType(String productionType) {
            this.productionType = productionType;
        }

        public List<Product> getProducts() {
            return products;
        }

        public void setProducts(List<Product> products) {
            this.products = products;
        }

        public String getRegDate() {
            return regDate;
        }

        public void setRegDate(String regDate) {
            this.regDate = regDate;
        }

        public String getRegNumber() {
            return regNumber;
        }

        public void setRegNumber(String regNumber) {
            this.regNumber = regNumber;
        }
    }

    /**
     * Внутренний класс, описывающий поле объекта документа.
     */
    public class Description {
        private String participantInn;

        public String getParticipantInn() {
            return participantInn;
        }

        public void setParticipantInn(String participantInn) {
            this.participantInn = participantInn;
        }
    }

    /**
     * Внутренний класс, описывающий поле объекта документа.
     */
    public class Product {
        private String certificateDocument;
        private String certificateDocumentDate;
        private String certificateDocumentNumber;
        private String ownerInn;
        private String producerInn;
        private String productionDate;
        private String tnvedCode;
        private String uitCode;
        private String uituCode;

        public String getCertificateDocument() {
            return certificateDocument;
        }

        public void setCertificateDocument(String certificateDocument) {
            this.certificateDocument = certificateDocument;
        }

        public String getCertificateDocumentDate() {
            return certificateDocumentDate;
        }

        public void setCertificateDocumentDate(String certificateDocumentDate) {
            this.certificateDocumentDate = certificateDocumentDate;
        }

        public String getCertificateDocumentNumber() {
            return certificateDocumentNumber;
        }

        public void setCertificateDocumentNumber(String certificateDocumentNumber) {
            this.certificateDocumentNumber = certificateDocumentNumber;
        }

        public String getOwnerInn() {
            return ownerInn;
        }

        public void setOwnerInn(String ownerInn) {
            this.ownerInn = ownerInn;
        }

        public String getProducerInn() {
            return producerInn;
        }

        public void setProducerInn(String producerInn) {
            this.producerInn = producerInn;
        }

        public String getProductionDate() {
            return productionDate;
        }

        public void setProductionDate(String productionDate) {
            this.productionDate = productionDate;
        }

        public String getTnvedCode() {
            return tnvedCode;
        }

        public void setTnvedCode(String tnvedCode) {
            this.tnvedCode = tnvedCode;
        }

        public String getUitCode() {
            return uitCode;
        }

        public void setUitCode(String uitCode) {
            this.uitCode = uitCode;
        }

        public String getUituCode() {
            return uituCode;
        }

        public void setUituCode(String uituCode) {
            this.uituCode = uituCode;
        }
    }
}