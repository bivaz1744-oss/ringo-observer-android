package com.ringoai.sixone;
import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;import android.hardware.display.VirtualDisplay;
import android.media.Image;import android.media.ImageReader;import android.media.projection.MediaProjection;import android.media.projection.MediaProjectionManager;
import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;
import com.google.mlkit.vision.common.InputImage;import com.google.mlkit.vision.text.Text;import com.google.mlkit.vision.text.TextRecognition;import com.google.mlkit.vision.text.TextRecognizer;import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.nio.ByteBuffer;import java.util.*;import java.util.regex.Matcher;import java.util.regex.Pattern;import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenOcrService extends Service {
 public static final String ACTION_NUMBER="com.ringoai.sixone.OCR_NUMBER";
 public static final String ACTION_STOP="com.ringoai.sixone.OCR_STOP";
 private static final String CHANNEL="ringo_screen_reader";
 private static final Pattern NUMBER_TOKEN=Pattern.compile("(?<![0-9])([1-9]|[12][0-9]|3[0-6])(?![0-9])");
 private static final Pattern COEFFICIENT_TOKEN=Pattern.compile("(?<![0-9])([0-9]+[\\.,][0-9]{3,6})(?![0-9])");
 private MediaProjection projection; private VirtualDisplay display; private ImageReader reader; private TextRecognizer recognizer;
 private WindowManager windowManager; private LinearLayout panel; private TextView forecastView,statusView,historyView;
 private WindowManager.LayoutParams layoutParams; private int panelX,panelY,frameWidth,frameHeight;
 private final AtomicBoolean processing=new AtomicBoolean(false); private long lastScanAt;
 private String pendingSequence=""; private int pendingSequenceFrames;

 @Override public void onCreate(){super.onCreate();recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"RINGO AI — автоматическое наблюдение",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(intent==null||!intent.hasExtra("data")){stopSelf();return START_NOT_STICKY;}
  Notification n=new Notification.Builder(this,CHANNEL).setContentTitle("RINGO AI 7.4 — автоанализ")
   .setContentText("Записывает историю и коэффициенты автоматически. Только подсказки.")
   .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
  if(Build.VERSION.SDK_INT>=29)startForeground(620,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(620,n);
  int code=intent.getIntExtra("resultCode",Activity.RESULT_CANCELED);Intent data=intent.getParcelableExtra("data");
  MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);projection=m.getMediaProjection(code,data);
  if(projection==null){stopSelf();return START_NOT_STICKY;}
  projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}},new Handler(Looper.getMainLooper()));
  showFloatingPanel();startCapture();return START_NOT_STICKY;
 }
 private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
 private TextView label(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(dp(6),dp(3),dp(6),dp(3));if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
 private void showFloatingPanel(){
  try{
   if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this))return;
   windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(6),dp(8),dp(6));
   GradientDrawable bg=new GradientDrawable();bg.setColor(0xEE132238);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xff42a5d8);panel.setBackground(bg);panel.setElevation(dp(8));
   TextView header=label("RINGO AI 7.4  •  ПЕРЕТАЩИ",12,true);panel.addView(header);
   header.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;public boolean onTouch(View v,MotionEvent e){
    if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=panelX;oy=panelY;return true;}
    if(e.getAction()==MotionEvent.ACTION_MOVE){panelX=ox+(int)(e.getRawX()-sx);panelY=oy+(int)(e.getRawY()-sy);updatePosition();return true;}return true;}});
   forecastView=label("МОЯ ПОДСКАЗКА: собираю историю…",16,true);panel.addView(forecastView);
   statusView=label("Сканирую верхнюю строку результатов игры",11,false);panel.addView(statusView);
   historyView=label("История: ожидаю данные",11,false);panel.addView(historyView);
   Button hide=new Button(this);hide.setText("Скрыть панель");hide.setTextSize(10);hide.setAllCaps(false);panel.addView(hide);
   hide.setOnClickListener(v->{try{windowManager.removeView(panel);panel=null;}catch(Exception ignored){}});
   int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
   int sw=getResources().getDisplayMetrics().widthPixels;int width=Math.min(dp(190),sw-dp(12));
   panelX=dp(8);panelY=dp(90);
   layoutParams=new WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,type,
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
   layoutParams.gravity=Gravity.TOP|Gravity.START;layoutParams.x=panelX;layoutParams.y=panelY;windowManager.addView(panel,layoutParams);refreshPanel();
  }catch(Exception e){panel=null;}
 }
 private void updatePosition(){if(panel!=null&&layoutParams!=null){layoutParams.x=panelX;layoutParams.y=panelY;try{windowManager.updateViewLayout(panel,layoutParams);}catch(Exception ignored){}}}
 private ArrayList<Integer> readHistory(){ArrayList<Integer>a=new ArrayList<>();String raw=getSharedPreferences("ringo70",0).getString("history","");for(String s:raw.split(","))try{int n=Integer.parseInt(s.trim());if(n>=1&&n<=37)a.add(n);}catch(Exception ignored){}return a;}
 private ArrayList<String> readCoefficients(){ArrayList<String>a=new ArrayList<>();String raw=getSharedPreferences("ringo70",0).getString("historyCoefficients","");for(String s:raw.split(",",-1))if(!s.trim().isEmpty())a.add(s.trim());return a;}
 private void refreshPanel(){
  if(panel==null)return;ArrayList<Integer> h=readHistory();ArrayList<String> c=readCoefficients();
  forecastView.setText(RingoBrain.buildRecommendation(h));
  int rounds=getSharedPreferences("ringo70",0).getInt("rounds",h.size());
  String last=h.isEmpty()?"—":(h.get(0)==37?"СТРЕЛКИ":String.valueOf(h.get(0)));
  String coef=c.isEmpty()?"—":c.get(0);
  statusView.setText("Сохранено: "+rounds+" • Последний: "+last+" • коэф. "+coef);
  StringBuilder b=new StringBuilder("Последние: ");for(int i=0;i<Math.min(5,h.size());i++){if(i>0)b.append(" | ");b.append(h.get(i)==37?"↔":h.get(i));if(i<c.size()&&!"-".equals(c.get(i)))b.append(" @").append(c.get(i));}
  historyView.setText(b.toString());
 }
 private void startCapture(){
  frameWidth=getResources().getDisplayMetrics().widthPixels;frameHeight=getResources().getDisplayMetrics().heightPixels;int density=getResources().getDisplayMetrics().densityDpi;
  reader=ImageReader.newInstance(frameWidth,frameHeight,PixelFormat.RGBA_8888,2);
  reader.setOnImageAvailableListener(source->{
   long now=SystemClock.elapsedRealtime();if(now-lastScanAt<700L){Image s=null;try{s=source.acquireLatestImage();}catch(Exception ignored){}if(s!=null)s.close();return;}
   if(!processing.compareAndSet(false,true)){Image s=null;try{s=source.acquireLatestImage();}catch(Exception ignored){}if(s!=null)s.close();return;}
   lastScanAt=now;Image image=null;
   try{
    image=source.acquireLatestImage();if(image==null){processing.set(false);return;}
    Image.Plane p=image.getPlanes()[0];ByteBuffer buf=p.getBuffer();int ps=p.getPixelStride(),rs=p.getRowStride(),padding=rs-ps*frameWidth;
    Bitmap padded=Bitmap.createBitmap(frameWidth+padding/ps,frameHeight,Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(buf);
    Bitmap full=Bitmap.createBitmap(padded,0,0,frameWidth,frameHeight);padded.recycle();image.close();image=null;
    // Game ticker from the submitted screen recording: x=10–69%, y=12–14.7%.
    int left=(int)(frameWidth*0.102f),top=(int)(frameHeight*0.119f),right=(int)(frameWidth*0.69f),bottom=(int)(frameHeight*0.147f);
    Bitmap strip=Bitmap.createBitmap(full,left,top,Math.max(1,right-left),Math.max(1,bottom-top));full.recycle();
    Bitmap enlarged=Bitmap.createScaledBitmap(strip,strip.getWidth()*4,strip.getHeight()*4,true);strip.recycle();
    recognizer.process(InputImage.fromBitmap(enlarged,0)).addOnSuccessListener(this::handleTickerText)
     .addOnFailureListener(e->{}).addOnCompleteListener(t->{enlarged.recycle();processing.set(false);});
   }catch(Exception e){if(image!=null)image.close();processing.set(false);}
  },new Handler(Looper.getMainLooper()));
  display=projection.createVirtualDisplay("RingoTickerReader",frameWidth,frameHeight,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
 }
 private void handleTickerText(Text text){
  ArrayList<Text.Line> numLines=new ArrayList<>(),coefLines=new ArrayList<>();
  int split=(int)(frameHeight*0.028f*4*0.52f);
  for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
   android.graphics.Rect r=line.getBoundingBox();if(r==null)continue;if(r.centerY()<split)numLines.add(line);else coefLines.add(line);
  }
  Comparator<Text.Line> byX=(a,b)->Integer.compare(a.getBoundingBox().left,b.getBoundingBox().left);
  Collections.sort(numLines,byX);Collections.sort(coefLines,byX);
  StringBuilder nt=new StringBuilder(),ct=new StringBuilder();for(Text.Line l:numLines)nt.append(" ").append(l.getText());for(Text.Line l:coefLines)ct.append(" ").append(l.getText());
  ArrayList<Integer> numbers=new ArrayList<>();Matcher nm=NUMBER_TOKEN.matcher(nt.toString());while(nm.find())try{numbers.add(Integer.parseInt(nm.group(1)));}catch(Exception ignored){}
  ArrayList<String> coeffs=new ArrayList<>();Matcher cm=COEFFICIENT_TOKEN.matcher(ct.toString());while(cm.find())coeffs.add(cm.group(1).replace(',','.'));
  if(numbers.size()<5||numbers.size()>9)return;
  String current=joinNumbers(numbers),coeffCsv=alignCoefficients(coeffs,numbers.size());
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);String previous=p.getString("tickerSequence","");
  if(previous.isEmpty()){
   if(p.getString("history","").trim().isEmpty()){
    p.edit().putString("history",current).putString("historyCoefficients",coeffCsv).putInt("rounds",numbers.size())
     .putString("tickerSequence",current).putString("tickerCoefficients",coeffCsv).apply();
    setStatus("Нашёл строку истории. Сохранил "+numbers.size()+" видимых исходов и коэффициенты.");
   }else{p.edit().putString("tickerSequence",current).putString("tickerCoefficients",coeffCsv).apply();setStatus("История распознана. Жду новый раунд автоматически.");}
   refreshPanel();broadcastUpdate(-1);return;
  }
  if(current.equals(previous)){pendingSequence="";pendingSequenceFrames=0;return;}
  ArrayList<Integer> old=parseNumbers(previous);int leftOverlap=0,rightOverlap=0;
  while(leftOverlap<Math.min(numbers.size()-1,old.size())&&numbers.get(leftOverlap+1).equals(old.get(leftOverlap)))leftOverlap++;
  while(rightOverlap<Math.min(numbers.size(),old.size()-1)&&numbers.get(rightOverlap).equals(old.get(rightOverlap+1)))rightOverlap++;
  int candidate=-1,index=-1;
  if(leftOverlap>=4&&leftOverlap>rightOverlap){candidate=numbers.get(0);index=0;}
  else if(rightOverlap>=4&&rightOverlap>leftOverlap){candidate=numbers.get(numbers.size()-1);index=numbers.size()-1;}
  if(candidate<1||candidate>36){pendingSequence="";pendingSequenceFrames=0;return;}
  if(current.equals(pendingSequence))pendingSequenceFrames++;else{pendingSequence=current;pendingSequenceFrames=1;}
  if(pendingSequenceFrames>=3){
   String coefficient=index<coeffs.size()?coeffs.get(index):"-";
   recordAutomatic(candidate,coefficient,current,coeffCsv);pendingSequence="";pendingSequenceFrames=0;
  }
 }
 private void recordAutomatic(int n,String coefficient,String ticker,String tickerCoefficients){
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);ArrayList<Integer>h=readHistory();ArrayList<String>c=readCoefficients();
  h.add(0,n);c.add(0,(coefficient==null||coefficient.isEmpty())?"-":coefficient);
  while(h.size()>500)h.remove(h.size()-1);while(c.size()>500)c.remove(c.size()-1);
  p.edit().putString("history",joinNumbers(h)).putString("historyCoefficients",joinStrings(c))
   .putString("tickerSequence",ticker).putString("tickerCoefficients",tickerCoefficients).putInt("rounds",p.getInt("rounds",0)+1).apply();
  setStatus("Новый исход записан автоматически: "+n+" • коэффициент "+coefficient);refreshPanel();broadcastUpdate(n);
 }
 private void setStatus(String value){if(statusView!=null)statusView.setText(value);}
 private void broadcastUpdate(int n){Intent i=new Intent(ACTION_NUMBER);i.setPackage(getPackageName());i.putExtra("number",n);i.putExtra("automatic",true);sendBroadcast(i);}
 private String joinNumbers(List<Integer>a){StringBuilder b=new StringBuilder();for(int n:a){if(b.length()>0)b.append(",");b.append(n);}return b.toString();}
 private String joinStrings(List<String>a){StringBuilder b=new StringBuilder();for(String s:a){if(b.length()>0)b.append(",");b.append(s);}return b.toString();}
 private String alignCoefficients(List<String>a,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++){if(i>0)b.append(",");b.append(i<a.size()?a.get(i):"-");}return b.toString();}
 private ArrayList<Integer> parseNumbers(String s){ArrayList<Integer>a=new ArrayList<>();for(String v:s.split(","))try{int n=Integer.parseInt(v.trim());if(n>=1&&n<=36)a.add(n);}catch(Exception ignored){}return a;}
 @Override public void onDestroy(){if(panel!=null&&windowManager!=null)try{windowManager.removeView(panel);}catch(Exception ignored){}if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();if(recognizer!=null)recognizer.close();stopForeground(true);super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
