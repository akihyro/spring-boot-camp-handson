package kanjava;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.function.BiConsumer;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.Part;

import static org.bytedeco.javacpp.opencv_core.CV_AA;
import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.opencv_core.Point;
import org.bytedeco.javacpp.opencv_core.Rect;
import org.bytedeco.javacpp.opencv_core.Scalar;
import org.bytedeco.javacpp.opencv_core.Size;
import static org.bytedeco.javacpp.opencv_core.circle;
import static org.bytedeco.javacpp.opencv_core.ellipse;
import static org.bytedeco.javacpp.opencv_core.rectangle;
import static org.bytedeco.javacpp.opencv_imgproc.INTER_LINEAR;
import static org.bytedeco.javacpp.opencv_imgproc.resize;
import org.bytedeco.javacpp.opencv_objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsMessagingTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@SpringBootApplication
@RestController
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    private static final Logger log = LoggerFactory.getLogger(App.class);

    @Autowired
    JmsMessagingTemplate jmsMessagingTemplate; // メッセージ操作用APIのJMSラッパー

    @Autowired // FaceDetectorをインジェクション
    FaceDetector faceDetector;

    @Autowired
    SimpMessagingTemplate simpMessagingTemplate;

    @Value("${faceduker.width:200}")
    int resizedWidth; // リサイズ後の幅

    @Bean // HTTPのリクエスト・レスポンスボディにBufferedImageを使えるようにする
    BufferedImageHttpMessageConverter bufferedImageHttpMessageConverter() {
        return new BufferedImageHttpMessageConverter();
    }

    @Configuration
    @EnableWebSocketMessageBroker // WebSocketに関する設定クラス
    static class StompConfig extends AbstractWebSocketMessageBrokerConfigurer {

        @Override
        public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("endpoint"); // WebSocketのエンドポイント
        }

        @Override
        public void configureMessageBroker(MessageBrokerRegistry registry) {
            registry.setApplicationDestinationPrefixes("/app"); // Controllerに処理させる宛先のPrefix
            registry.enableSimpleBroker("/topic"); // queueまたはtopicを有効にする(両方可)。queueは1対1(P2P)、topicは1対多(Pub-Sub)
        }

        @Override
        public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
            registration.setMessageSizeLimit(10 * 1024 * 1024); // メッセージサイズの上限を10MBに上げる(デフォルトは64KB)
        }

    }

    @RequestMapping(value = "/")
    String hello() {
        return "Hello World!";
    }

    // curl -v -F 'file=@hoge.jpg' http://localhost:8080/duker > after.jpg という風に使えるようにする
    @RequestMapping(value = "/duker", method = RequestMethod.POST) // POSTで/dukerへのリクエストに対する処理
    BufferedImage duker(@RequestParam Part file /* パラメータ名fileのマルチパートリクエストのパラメータを取得 */) throws IOException {
        Mat source = Mat.createFrom(ImageIO.read(file.getInputStream())); // Part -> BufferedImage -> Matと変換
        faceDetector.detectFaces(source, FaceTranslator::necobeanize);
        BufferedImage image = source.getBufferedImage(); // Mat -> BufferedImage
        return image;
    }

    @RequestMapping(value = "/send")
    String send(@RequestParam String msg /* リクエストパラメータmsgでメッセージ本文を受け取る */) {
        Message<String> message = MessageBuilder
                .withPayload(msg)
                .build(); // メッセージを作成
        jmsMessagingTemplate.send("hello", message); // 宛先helloにメッセージを送信
        return "OK"; // とりあえずOKと即時応答しておく
    }

    //@JmsListener(destination = "hello" /* 処理するメッセージの宛先を指定 */, concurrency = "1-5" /* 最小1スレッド、最大5スレッドに設定 */)
    void handleHelloMessage(Message<String> message /* 送信されたメッセージを受け取る */) {
        log.info("received! {}", message);
        log.info("msg={}", message.getPayload());
    }

    @RequestMapping(value = "/queue", method = RequestMethod.POST)
    String queue(@RequestParam Part file) throws IOException {
        byte[] src = StreamUtils.copyToByteArray(file.getInputStream()); // InputStream -> byte[]
        Message<byte[]> message = MessageBuilder.withPayload(src).build(); // byte[]を持つMessageを作成
        jmsMessagingTemplate.send("faceConverter", message); // convertAndSend("faceConverter", src)でも可
        return "OK";
    }

    @JmsListener(destination = "faceConverter", concurrency = "1-5")
    void convertFace(Message<byte[]> message) throws IOException {
        log.info("received! {}", message);
        try (InputStream stream = new ByteArrayInputStream(message.getPayload())) { // byte[] -> InputStream
            Mat source = Mat.createFrom(ImageIO.read(stream)); // InputStream -> BufferedImage -> Mat
            faceDetector.detectFaces(source, FaceTranslator::necobeanize);

            // リサイズ
            //BufferedImage image = source.getBufferedImage();
            double ratio = ((double) resizedWidth) / source.cols();
            int height = (int) (ratio * source.rows());
            Mat out = new Mat(height, resizedWidth, source.type());
            resize(source, out, new Size(), ratio, ratio, INTER_LINEAR);
            BufferedImage image = out.getBufferedImage();

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { // BufferedImageをbyte[]に変換
                ImageIO.write(image, "png", baos);
                baos.flush();
                // 画像をBase64にエンコードしてメッセージ作成し、宛先'/topic/faces'へメッセージ送信
                simpMessagingTemplate.convertAndSend("/topic/faces",
                        Base64.getEncoder().encodeToString(baos.toByteArray()));
            }
        }
    }

    @MessageMapping(value = "/greet" /* 宛先名 */) // Controller内の@MessageMappingアノテーションをつけたメソッドが、メッセージを受け付ける
    @SendTo(value = "/topic/greetings") // 処理結果の送り先
    String greet(String name) {
        log.info("received {}", name);
        return "Hello " + name;
    }

    @MessageMapping(value = "/faceConverter")
    void faceConverter(String base64Image) {
        Message<byte[]> message = MessageBuilder.withPayload(Base64.getDecoder().decode(base64Image)).build();
        jmsMessagingTemplate.send("faceConverter", message);
    }

}

@Component
@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
class FaceDetector {
    // 分類器のパスをプロパティから取得できるようにする
    @Value("${classifierFile:classpath:/haarcascade_frontalface_default.xml}")
    File classifierFile;

    CascadeClassifier classifier;

    static final Logger log = LoggerFactory.getLogger(FaceDetector.class);

    public void detectFaces(Mat source, BiConsumer<Mat, Rect> detectAction) {
        // 顔認識結果
        Rect faceDetections = new Rect();
        // 顔認識実行
        classifier.detectMultiScale(source, faceDetections);
        // 認識した顔の数
        int numOfFaces = faceDetections.limit();
        log.info("{} faces are detected!", numOfFaces);
        for (int i = 0; i < numOfFaces; i++) {
            // i番目の認識結果
            Rect r = faceDetections.position(i);
            // 1件ごとの認識結果を変換処理(関数)にかける
            detectAction.accept(source, r);
        }
    }

    @PostConstruct // 初期化処理。DIでプロパティがセットされたあとにclassifierインスタンスを生成したいのでここで書く。
    void init() throws IOException {
        if (log.isInfoEnabled()) {
            log.info("load {}", classifierFile.toPath());
        }
        // 分類器の読み込み
        this.classifier = new CascadeClassifier(classifierFile.toPath()
                .toString());
    }
}

class FaceTranslator {
    public static void duker(Mat source, Rect r) { // BiConsumer<Mat, Rect>で渡せるようにする
        int x = r.x(), y = r.y(), h = r.height(), w = r.width();
        // Dukeのように描画する
        // 上半分の黒四角
        rectangle(source, new Point(x, y), new Point(x + w, y + h / 2),
                new Scalar(0, 0, 0, 0), -1, CV_AA, 0);
        // 下半分の白四角
        rectangle(source, new Point(x, y + h / 2), new Point(x + w, y + h),
                new Scalar(255, 255, 255, 0), -1, CV_AA, 0);
        // 中央の赤丸
        circle(source, new Point(x + h / 2, y + h / 2), (w + h) / 12,
                new Scalar(0, 0, 255, 0), -1, CV_AA, 0);
    }

    // ねこびーんっぽいのを描画するっす
    public static void necobeanize(Mat source, Rect r) {
        int x = r.x(), y = r.y(), h = r.height(), w = r.width();
        rectangle(source, r, new Scalar(249, 214, 150, 0), -1, CV_AA, 0);
        circle(source, new Point(x + w * 1 / 6, y + h / 2), (w + h) / 30,
                new Scalar(0, 0, 0, 0), -1, CV_AA, 0);
        circle(source, new Point(x + w * 5 / 6, y + h / 2), (w + h) / 30,
                new Scalar(0, 0, 0, 0), -1, CV_AA, 0);
        ellipse(source, new Point(x + w * 3 / 8, y + h * 2 / 3),
                new Size(w * 1 / 8, h * 1 / 12), 0, 0, 180,
                new Scalar(0, 0, 0, 0), (w + h) / 30, CV_AA, 0);
        ellipse(source, new Point(x + w * 5 / 8, y + h * 2 / 3),
                new Size(w * 1 / 8, h * 1 / 12), 0, 0, 180,
                new Scalar(0, 0, 0, 0), (w + h) / 30, CV_AA, 0);
    }

}
