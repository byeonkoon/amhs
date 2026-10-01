package com.amhs.platform;
import org.junit.jupiter.api.Test;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

/** A reason written in Korean reaches the audit as written, whatever the header had to carry. */
class RequestReasonTest {
 private static String encodeLikeTheBrowser(String text){
  // encodeURIComponent: UTF-8 percent-encoding, spaces as %20.
  return URLEncoder.encode(text,StandardCharsets.UTF_8).replace("+","%20");
 }

 @Test void aPercentEncodedKoreanReasonIsReadBack(){
  assertThat(RequestReason.fromHeader(encodeLikeTheBrowser("시스템 옵션 변경"))).isEqualTo("시스템 옵션 변경");
 }
 @Test void plainAsciiIsUnchangedIncludingAPlusSign(){
  assertThat(RequestReason.fromHeader("operator stop")).isEqualTo("operator stop");
  assertThat(RequestReason.fromHeader("a+b")).isEqualTo("a+b");
 }
 @Test void somethingThatIsNotValidEncodingIsKeptAsSent(){
  assertThat(RequestReason.fromHeader("100%")).isEqualTo("100%");
  assertThat(RequestReason.fromHeader(null)).isNull();
 }
}
