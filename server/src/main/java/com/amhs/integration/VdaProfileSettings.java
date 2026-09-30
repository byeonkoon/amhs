package com.amhs.integration;
import com.amhs.platform.*;
import java.util.regex.Pattern;
/**
 * Packet and action settings shared by VDA vehicles of one make and version.
 * Topic version (v2, v3) and JSON version (1.0.0, 3.0.0) are kept apart because they do not follow each other.
 * sourceAction is sent at the pickup port and destAction at the drop port.
 */
public record VdaProfileSettings(String code,String interfaceName,String topicVersion,String manufacturer,
 String jsonVersion,String sourceAction,String destAction){
 private static final Pattern CODE=Pattern.compile("[A-Z0-9_-]{1,32}");
 private static final Pattern TOPIC_VERSION=Pattern.compile("v\\d{1,3}");
 private static final Pattern JSON_VERSION=Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
 private static final Pattern ACTION=Pattern.compile("[A-Za-z0-9_.-]{1,64}");
 public void validate(){
  ConfigurationException.require(code!=null&&CODE.matcher(code).matches(),"INVALID_VDA_PROFILE_CODE");
  topicComponent(interfaceName,"INTERFACE_NAME");topicComponent(manufacturer,"MANUFACTURER");
  ConfigurationException.require(topicVersion!=null&&TOPIC_VERSION.matcher(topicVersion).matches(),"INVALID_TOPIC_VERSION");
  ConfigurationException.require(jsonVersion!=null&&JSON_VERSION.matcher(jsonVersion).matches(),"INVALID_JSON_VERSION");
  ConfigurationException.require(sourceAction!=null&&ACTION.matcher(sourceAction).matches(),"INVALID_SOURCE_ACTION");
  ConfigurationException.require(destAction!=null&&ACTION.matcher(destAction).matches(),"INVALID_DEST_ACTION");
  ConfigurationException.require(!sourceAction.equals(destAction),"VDA_ACTIONS_MUST_DIFFER");
 }
 /** These values become MQTT topic levels, so topic separators and wildcards are not allowed. */
 private static void topicComponent(String value,String field){
  ConfigurationInput.text(value,64,field);
  ConfigurationException.require(value.chars().noneMatch(c->c=='/'||c=='+'||c=='#'||Character.isWhitespace(c)||Character.isISOControl(c)),"INVALID_"+field);
 }
}
