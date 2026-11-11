package probe;
import android.app.Activity;
import android.os.Bundle;
import android.net.wifi.WifiManager;
import android.util.Log;
import bridgeprobe.*;
public class ProbeActivity extends Activity {
 protected void onCreate(Bundle b) {
  super.onCreate(b);
  final String mode=getIntent().getStringExtra("mode");
  new Thread(()->{
   try {
    String value=Bridgeprobe.run(new Platform(){
     private String state(){
      if(mode.contains("real")) return ((WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo().getSSID();
      throw new SecurityException("synthetic callback failure");
     }
     public String oldWIFIState(){return state();}
     public String safeWIFIState(){return state();}
     public long owner(){return 123;}
    },mode.startsWith("safe"));
    Log.i("ThBoxArtProbe",mode+" "+value);
   }catch(Throwable e){Log.e("ThBoxArtProbe",mode+" error-class="+e.getClass().getName());}
  }).start();
 }
}
