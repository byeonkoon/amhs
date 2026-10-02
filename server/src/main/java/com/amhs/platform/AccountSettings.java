package com.amhs.platform;
/** What an administrator may set on an account. The token is never set by hand. */
public record AccountSettings(String code,String displayName,String role,boolean enabled){
 public Role parsedRole(){ return Role.of(role); }
 public void validate(){
  ConfigurationInput.text(code,64,"CODE");
  ConfigurationInput.text(displayName,128,"DISPLAY_NAME");
  parsedRole();
 }
}
