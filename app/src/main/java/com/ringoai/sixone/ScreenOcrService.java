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
 private static final Pattern RESULT_TOKEN=Pattern.compile("(?<![0-9])([1-9]|[12][0-9]|3[0-7])(?![0-9])|[↔⇄⟷↕↑↓▲▼◀▶]|СТРЕЛКИ|ARROWS",Pattern.CASE_INSENSITIVE);
 private static final Pattern COEFFICIENT_TOKEN=Pattern.compile("(?<![0-9])([0-9]{1,2}[\\.,][0-9]{3,6})(?![0-9])");
 private static final int BOARD_CELLS=36;
 private MediaProjection projection; private VirtualDisplay display; private ImageReader reader; private TextRecognizer recognizer; private PowerManager.WakeLock wakeLock;
 private WindowManager windowManager; private LinearLayout panel; private TextView forecastView,statusView,historyView;
 private WindowManager.LayoutParams layoutParams; private int panelX,panelY,frameWidth,frameHeight;
 private final AtomicBoolean processing=new AtomicBoolean(false); private long lastScanAt;
 private String pendingSequence=""; private int pendingSequenceFrames; private String latestBoardCsv=""; private int latestBoardCount; private long lastBoardSampleAt;

 @Override public void onCreate(){super.onCreate();recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"RINGO AI — автоматическое наблюдение",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(intent==null||!intent.hasExtra("data")){stopSelf();return START_NOT_STICKY;}
  Notification n=new Notification.Builder(this,CHANNEL).setContentTitle("RINGO AI 7.4 — автоанализ")
   .setContentText("Считывает 36 ячеек и ленту, сохраняет историю. Только подсказки.")
   .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
  if(Build.VERSION.SDK_INT>=29)startForeground(620,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(620,n);
  try{PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RingoAI::ScreenObserver");wakeLock.acquire();}catch(Exception ignored){}
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
   TextView header=label("RINGO AI 7.5",10,true);panel.addView(header);
   header.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;public boolean onTouch(View v,MotionEvent e){
    if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=panelX;oy=panelY;return true;}
    if(e.getAction()==MotionEvent.ACTION_MOVE){panelX=ox+(int)(e.getRawX()-sx);panelY=oy+(int)(e.getRawY()-sy);updatePosition();return true;}return true;}});
   forecastView=label("МОЯ ПОДСКАЗКА: собираю историю…",10,true);panel.addView(forecastView);
   statusView=label("Читаю поле 36 ячеек и историю",8,false);panel.addView(statusView);
   historyView=label("История: ожидаю данные",8,false);panel.addView(historyView);
   Button hide=new Button(this);hide.setText("Скрыть панель");hide.setTextSize(10);hide.setAllCaps(false);panel.addView(hide);
   hide.setOnClickListener(v->{try{windowManager.removeView(panel);panel=null;}catch(Exception ignored){}});
   int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
   int sw=getResources().getDisplayMetrics().widthPixels;int width=Math.min(dp(108),sw-dp(12));
   panelX=Math.max(dp(4),sw-width-dp(4));panelY=dp(92);
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
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
  String board=p.getString("boardLatest","");String samples=p.getString("boardSamples","");
  forecastView.setText(RingoBrain.buildRecommendation(h,board,samples));
  int rounds=p.getInt("rounds",h.size());
  String last=h.isEmpty()?"—":(h.get(0)==37?"СТРЕЛКИ":String.valueOf(h.get(0)));
  String coef=c.isEmpty()?"—":c.get(0);
  int seen=p.getInt("boardCells",0);statusView.setText("Поле: "+seen+"/36 • раундов: "+rounds+" • последний: "+last+" @"+coef);
  StringBuilder b=new StringBuilder("Последние: ");for(int i=0;i<Math.min(4,h.size());i++){if(i>0)b.append(" | ");b.append(h.get(i)==37?"↔":h.get(i));if(i<c.size()&&!"-".equals(c.get(i)))b.append(" @").append(c.get(i));}
  historyView.setText(b.toString());
 }
 private void startCapture(){
  frameWidth=getResources().getDisplayMetrics().widthPixels;frameHeight=getResources().getDisplayMetrics().heightPixels;int density=getResources().getDisplayMetrics().densityDpi;
  reader=ImageReader.newInstance(frameWidth,frameHeight,PixelFormat.RGBA_8888,2);
  reader.setOnImageAvailableListener(source->{
   long now=SystemClock.elapsedRealtime();if(now-lastScanAt<1200L){Image s=null;try{s=source.acquireLatestImage();}catch(Exception ignored){}if(s!=null)s.close();return;}
   if(!processing.compareAndSet(false,true)){Image s=null;try{s=source.acquireLatestImage();}catch(Exception ignored){}if(s!=null)s.close();return;}
   lastScanAt=now;Image image=null;
   try{
    image=source.acquireLatestImage();if(image==null){processing.set(false);return;}
    Image.Plane p=image.getPlanes()[0];ByteBuffer buf=p.getBuffer();int ps=p.getPixelStride(),rs=p.getRowStride(),padding=rs-ps*frameWidth;
    Bitmap padded=Bitmap.createBitmap(frameWidth+padding/ps,frameHeight,Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(buf);
    Bitmap full=Bitmap.createBitmap(padded,0,0,frameWidth,frameHeight);padded.recycle();image.close();image=null;
    // Capture the top result strip and the complete visible 3x12 game grid.
    int cropLeft=0,cropTop=(int)(frameHeight*0.105f),cropRight=Math.min(frameWidth,(int)(frameWidth*0.70f));
    Bitmap scan=Bitmap.createBitmap(full,cropLeft,cropTop,Math.max(1,cropRight-cropLeft),Math.max(1,frameHeight-cropTop));full.recycle();
    float scale=1.6f;
    Bitmap enlarged=Bitmap.createScaledBitmap(scan,Math.max(1,(int)(scan.getWidth()*scale)),Math.max(1,(int)(scan.getHeight()*scale)),true);scan.recycle();
    final int savedLeft=cropLeft,savedTop=cropTop;
    recognizer.process(InputImage.fromBitmap(enlarged,0)).addOnSuccessListener(t->handleScreenText(t,savedLeft,savedTop,scale))
     .addOnFailureListener(e->{}).addOnCompleteListener(t->{enlarged.recycle();processing.set(false);});
   }catch(Exception e){if(image!=null)image.close();processing.set(false);}
  },new Handler(Looper.getMainLooper()));
  display=projection.createVirtualDisplay("RingoScreenObserver",frameWidth,frameHeight,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
 }
 private void handleScreenText(Text text,int cropLeft,int cropTop,float scale){
  int[] tickerNumbers=new int[7];Arrays.fill(tickerNumbers,-1);
  String[] tickerCoefs=new String[7];Arrays.fill(tickerCoefs,"-");
  String[] board=new String[BOARD_CELLS];Arrays.fill(board,"-");
  float tickStart=frameWidth*0.10f,tickEnd=frameWidth*0.69f,tickStep=(tickEnd-tickStart)/7f;
  float gridLeft=frameWidth*0.0667f,gridTop=frameHeight*0.2244f;
  float tileWidth=frameWidth*0.190f,xStep=frameWidth*0.2014f;
  float tileHeight=frameHeight*0.065f,yStep=frameHeight*0.06875f;
  for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
   android.graphics.Rect r=line.getBoundingBox();if(r==null)continue;
   float x=cropLeft+r.centerX()/scale,y=cropTop+r.centerY()/scale;String raw=line.getText();
   if(y>=frameHeight*0.115f&&y<=frameHeight*0.155f){
    int bin=(int)((x-tickStart)/tickStep);
    if(bin>=0&&bin<7){
     if(y<frameHeight*0.1335f){Integer n=parseResultToken(raw);if(n!=null)tickerNumbers[bin]=n;}
     else{String c=parseCoefficient(raw);if(c!=null)tickerCoefs[bin]=c;}
    }
   }
   if(x>=gridLeft&&x<=gridLeft+2*xStep+tileWidth&&y>=gridTop){
    int col=(int)((x-gridLeft)/xStep),row=(int)((y-gridTop)/yStep);
    if(col>=0&&col<3&&row>=0&&row<12){
     float cellX=gridLeft+col*xStep,cellY=gridTop+row*yStep;
     float relY=(y-cellY)/tileHeight;
     if(x<=cellX+tileWidth&&y<=cellY+tileHeight*1.12f&&relY>=0.62f){
      String c=parseCoefficient(raw);if(c!=null)board[row*3+col]=c;
     }
    }
   }
  }
  ArrayList<Integer> numbers=new ArrayList<>();ArrayList<String> coeffs=new ArrayList<>();
  for(int i=0;i<7;i++)if(tickerNumbers[i]>=1&&tickerNumbers[i]<=37){numbers.add(tickerNumbers[i]);coeffs.add(tickerCoefs[i]);}
  int boardCount=0;for(String v:board)if(!"-".equals(v))boardCount++;
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
  if(boardCount>=18){
   latestBoardCsv=joinStrings(Arrays.asList(board));latestBoardCount=boardCount;
   p.edit().putString("boardLatest",latestBoardCsv).putInt("boardCells",boardCount).putLong("boardUpdatedAt",System.currentTimeMillis()).apply();
   long wall=System.currentTimeMillis();
   if(wall-lastBoardSampleAt>=60000L){appendBoardSample(0,latestBoardCsv);lastBoardSampleAt=wall;}
  }
  if(numbers.size()<5||numbers.size()>9){
   if(boardCount>=18)setStatus("Поле считано: "+boardCount+"/36. Лента ожидает новый результат.");
   else setStatus("Служба активна. Жду возвращения игрового поля на экран.");
   return;
  }
  String current=joinNumbers(numbers),coeffCsv=alignCoefficients(coeffs,numbers.size());
  String previous=p.getString("tickerSequence","");
  if(previous.isEmpty()){
   p.edit().putString("history",current).putString("historyCoefficients",coeffCsv).putInt("rounds",numbers.size())
    .putString("tickerSequence",current).putString("tickerCoefficients",coeffCsv).apply();
   if(!latestBoardCsv.isEmpty())appendBoardSample(0,latestBoardCsv);
   setStatus("Запомнил видимую ленту ("+numbers.size()+") и поле "+latestBoardCount+"/36.");
   refreshPanel();broadcastUpdate(-1);return;
  }
  if(current.equals(previous)){
   pendingSequence="";pendingSequenceFrames=0;
   if(boardCount>=18)setStatus("Наблюдение активно: поле "+boardCount+"/36. Жду следующий раунд.");
   return;
  }
  ArrayList<Integer> old=parseNumbers(previous);int leftOverlap=0,rightOverlap=0;
  while(leftOverlap<Math.min(numbers.size()-1,old.size())&&numbers.get(leftOverlap+1).equals(old.get(leftOverlap)))leftOverlap++;
  while(rightOverlap<Math.min(numbers.size(),old.size()-1)&&numbers.get(rightOverlap).equals(old.get(rightOverlap+1)))rightOverlap++;
  int candidate=-1,index=-1;
  if(leftOverlap>=4&&leftOverlap>rightOverlap){candidate=numbers.get(0);index=0;}
  else if(rightOverlap>=4&&rightOverlap>leftOverlap){candidate=numbers.get(numbers.size()-1);index=numbers.size()-1;}
  if(candidate<1||candidate>37){pendingSequence="";pendingSequenceFrames=0;return;}
  if(current.equals(pendingSequence))pendingSequenceFrames++;else{pendingSequence=current;pendingSequenceFrames=1;}
  if(pendingSequenceFrames>=3){
   String coefficient=index<coeffs.size()?coeffs.get(index):"-";
   recordAutomatic(candidate,coefficient,current,coeffCsv);pendingSequence="";pendingSequenceFrames=0;
  }
 }
 private Integer parseResultToken(String raw){
  Matcher m=RESULT_TOKEN.matcher(raw);if(!m.find())return null;
  try{return m.group(1)==null?37:Integer.parseInt(m.group(1));}catch(Exception ignored){return null;}
 }
 private String parseCoefficient(String raw){
  Matcher m=COEFFICIENT_TOKEN.matcher(raw);if(!m.find())return null;return m.group(1).replace(',','.');
 }
 private void appendBoardSample(int outcome,String csv){
  if(csv==null||csv.isEmpty())return;
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
  String old=p.getString("boardSamples","");String row=System.currentTimeMillis()+"|"+outcome+"|"+csv;
  String[] lines=old.isEmpty()?new String[0]:old.split("\n");
  int keep=Math.min(lines.length,1799);StringBuilder b=new StringBuilder();
  for(int i=lines.length-keep;i<lines.length;i++){if(b.length()>0)b.append('\n');b.append(lines[i]);}
  if(b.length()>0)b.append('\n');b.append(row);
  p.edit().putString("boardSamples",b.toString()).apply();
 }
 private void recordAutomatic(int n,String coefficient,String ticker,String tickerCoefficients){
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);ArrayList<Integer>h=readHistory();ArrayList<String>c=readCoefficients();
  h.add(0,n);c.add(0,(coefficient==null||coefficient.isEmpty())?"-":coefficient);
  while(h.size()>500)h.remove(h.size()-1);while(c.size()>500)c.remove(c.size()-1);
  p.edit().putString("history",joinNumbers(h)).putString("historyCoefficients",joinStrings(c))
   .putString("tickerSequence",ticker).putString("tickerCoefficients",tickerCoefficients).putInt("rounds",p.getInt("rounds",0)+1).apply();
  if(!latestBoardCsv.isEmpty()){appendBoardSample(n,latestBoardCsv);lastBoardSampleAt=System.currentTimeMillis();}
  setStatus("Новый исход записан автоматически: "+n+" • коэффициент "+coefficient);refreshPanel();broadcastUpdate(n);
 }
 private void setStatus(String value){if(statusView!=null)statusView.setText(value);}
 private void broadcastUpdate(int n){Intent i=new Intent(ACTION_NUMBER);i.setPackage(getPackageName());i.putExtra("number",n);i.putExtra("automatic",true);sendBroadcast(i);}
 private String joinNumbers(List<Integer>a){StringBuilder b=new StringBuilder();for(int n:a){if(b.length()>0)b.append(",");b.append(n);}return b.toString();}
 private String joinStrings(List<String>a){StringBuilder b=new StringBuilder();for(String s:a){if(b.length()>0)b.append(",");b.append(s);}return b.toString();}
 private String alignCoefficients(List<String>a,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++){if(i>0)b.append(",");b.append(i<a.size()?a.get(i):"-");}return b.toString();}
 private ArrayList<Integer> parseNumbers(String s){ArrayList<Integer>a=new ArrayList<>();for(String v:s.split(","))try{int n=Integer.parseInt(v.trim());if(n>=1&&n<=37)a.add(n);}catch(Exception ignored){}return a;}
 @Override public void onDestroy(){if(panel!=null&&windowManager!=null)try{windowManager.removeView(panel);}catch(Exception ignored){}if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();if(recognizer!=null)recognizer.close();if(wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}stopForeground(true);super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
