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
 private static final Pattern COEFFICIENT_TOKEN=Pattern.compile("(?<![0-9])(?:[xх]\\s*)?([0-9]{1,3}[\\.,][0-9]{1,6})\\s*[xх]?(?![0-9])",Pattern.CASE_INSENSITIVE);
 private static final int BOARD_CELLS=36;
 private MediaProjection projection; private VirtualDisplay display; private ImageReader reader; private TextRecognizer recognizer; private PowerManager.WakeLock wakeLock;
 private WindowManager windowManager; private LinearLayout panel; private TextView forecastView,statusView,historyView;
 private WindowManager.LayoutParams layoutParams; private int panelX,panelY,frameWidth,frameHeight;
 private final AtomicBoolean processing=new AtomicBoolean(false); private long lastScanAt; private RingoStore store;
 private String pendingSequence=""; private int pendingSequenceFrames; private String latestBoardCsv=""; private int latestBoardCount; private long latestBoardAt;

 @Override public void onCreate(){super.onCreate();store=new RingoStore(this);recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"RINGO AI — автоматическое наблюдение",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(intent==null||!intent.hasExtra("data")){stopSelf();return START_NOT_STICKY;}
  Notification.Builder notificationBuilder = Build.VERSION.SDK_INT >= 26
   ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
  Notification n=notificationBuilder.setContentTitle("RINGO AI 7.7 — автоанализ")
   .setContentText("Наблюдение экрана и сохранение подтверждённых результатов.")
   .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
   .setPriority(Notification.PRIORITY_MIN).build();
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
   TextView header=label("RINGO AI 7.7",10,true);panel.addView(header);
   header.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;public boolean onTouch(View v,MotionEvent e){
    if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();ox=panelX;oy=panelY;return true;}
    if(e.getAction()==MotionEvent.ACTION_MOVE){panelX=ox+(int)(e.getRawX()-sx);panelY=oy+(int)(e.getRawY()-sy);updatePosition();return true;}return true;}});
   forecastView=label("МОЯ ПОДСКАЗКА: собираю историю…",10,true);panel.addView(forecastView);
   statusView=label("Читаю поле 36 ячеек и историю",8,false);panel.addView(statusView);
   historyView=label("История: ожидаю данные",8,false);panel.addView(historyView);
   Button hide=new Button(this);hide.setText("Скрыть панель");hide.setTextSize(10);hide.setAllCaps(false);panel.addView(hide);
   hide.setOnClickListener(v->{try{windowManager.removeView(panel);panel=null;}catch(Exception ignored){}});
   int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
   int sw=getResources().getDisplayMetrics().widthPixels;int width=Math.min(dp(280),sw-dp(12));
   panelX=Math.max(dp(4),sw-width-dp(4));panelY=dp(92);
   layoutParams=new WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,type,
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
   layoutParams.gravity=Gravity.TOP|Gravity.START;layoutParams.x=panelX;layoutParams.y=panelY;windowManager.addView(panel,layoutParams);refreshPanel();
  }catch(Exception e){panel=null;}
 }
 private void updatePosition(){if(panel!=null&&layoutParams!=null){layoutParams.x=panelX;layoutParams.y=panelY;try{windowManager.updateViewLayout(panel,layoutParams);}catch(Exception ignored){}}}
 private ArrayList<Integer> readHistory(){return store.readHistory(500);}
 private ArrayList<String> readCoefficients(){return store.readCoefficients(500);}
 private void refreshPanel(){
  if(panel==null)return;ArrayList<Integer> h=readHistory();ArrayList<String> c=readCoefficients();
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
  String board=p.getString("boardLatest","");String samples=store.readBoardSamplesCsv(2000);
  forecastView.setText(RingoBrain.buildRecommendation(h,board,samples));
  int rounds=(int)Math.min(Integer.MAX_VALUE,store.getRoundCount());
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
   long now=SystemClock.elapsedRealtime();if(now-lastScanAt<700L){Image s=null;try{s=source.acquireLatestImage();}catch(Exception ignored){}if(s!=null)s.close();return;}
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
  float[] labelY=new float[BOARD_CELLS];Arrays.fill(labelY,Float.NaN);
  int[] labelCol=new int[BOARD_CELLS];Arrays.fill(labelCol,-1);
  ArrayList<Float> coefficientY=new ArrayList<>();ArrayList<Integer> coefficientCol=new ArrayList<>();ArrayList<String> coefficientValues=new ArrayList<>();
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
   if(x>=gridLeft&&x<=gridLeft+2*xStep+tileWidth&&y>=frameHeight*0.18f){
    int col=(int)((x-gridLeft)/xStep);
    if(col>=0&&col<3&&x<=gridLeft+col*xStep+tileWidth){
     Integer cellNo=parseCellLabel(raw);
     if(cellNo!=null){labelY[cellNo-1]=y;labelCol[cellNo-1]=col;}
     String c=parseCoefficient(raw);
     if(c!=null){coefficientY.add(y);coefficientCol.add(col);coefficientValues.add(c);}
    }
   }
  }
  ArrayList<Integer> numbers=new ArrayList<>();ArrayList<String> coeffs=new ArrayList<>();
  for(int i=0;i<7;i++)if(tickerNumbers[i]>=1&&tickerNumbers[i]<=37){numbers.add(tickerNumbers[i]);coeffs.add(tickerCoefs[i]);}
  int labelsFound=0;for(int i=0;i<BOARD_CELLS;i++)if(labelCol[i]>=0)labelsFound++;
  for(int i=0;i<coefficientValues.size();i++){
   int col=coefficientCol.get(i);float cy=coefficientY.get(i);String value=coefficientValues.get(i);
   int best=-1;float bestGap=Float.MAX_VALUE;
   if(labelsFound>=8){
    for(int cell=0;cell<BOARD_CELLS;cell++)if(labelCol[cell]==col&&!Float.isNaN(labelY[cell])){
     float gap=cy-labelY[cell];
     if(gap>=tileHeight*0.45f&&gap<=tileHeight*1.20f&&gap<bestGap){best=cell;bestGap=gap;}
    }
   }
   if(best>=0)board[best]=value;
   else if(labelsFound<8){
    int row=(int)((cy-gridTop)/yStep);
    if(row>=0&&row<12){
     float cellY=gridTop+row*yStep;
     float relY=(cy-cellY)/tileHeight;
     if(relY>=0.62f&&relY<=1.12f)board[row*3+col]=value;
    }
   }
  }
  int boardCount=0;for(String v:board)if(!"-".equals(v))boardCount++;
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
  if(boardCount>=30){
   latestBoardCsv=joinStrings(Arrays.asList(board));latestBoardCount=boardCount;latestBoardAt=System.currentTimeMillis();
   p.edit().putString("boardLatest",latestBoardCsv).putInt("boardCells",boardCount).putLong("boardUpdatedAt",latestBoardAt).apply();
  }
  if(numbers.size()<5||numbers.size()>9){
   if(boardCount>=30)setStatus("Поле распознано: "+boardCount+"/36. Лента ожидает новый результат.");
   else if(boardCount>0)setStatus("Поле распознано частично: "+boardCount+"/36. Снимок не сохраняю.");
   else setStatus("Служба активна. Жду игровое поле на экране.");
   return;
  }
  String current=joinNumbers(numbers),coeffCsv=alignCoefficients(coeffs,numbers.size());
  String previous=p.getString("tickerSequence","");
  if(previous.isEmpty()){
   // Seed the visible strip only when this installation has no older saved rounds.
   store.seedFromVisibleStrip(numbers,coeffs);
   p.edit().putInt("rounds",(int)Math.min(Integer.MAX_VALUE,store.getRoundCount()))
    .putString("tickerSequence",current).putString("tickerCoefficients",coeffCsv).apply();
   setStatus("Лента синхронизирована: "+numbers.size()+" видимых исходов. Всего сохранено: "+store.getRoundCount()+".");
   refreshPanel();broadcastUpdate(-1);return;
  }
  if(current.equals(previous)){
   pendingSequence="";pendingSequenceFrames=0;
   if(boardCount>=30)setStatus("Наблюдение активно: поле "+boardCount+"/36. Жду следующий раунд.");
   return;
  }
  ArrayList<Integer> old=parseNumbers(previous);
  int index=inferNewTickerIndex(numbers,old);
  int candidate=index>=0&&index<numbers.size()?numbers.get(index):-1;
  if(candidate<1||candidate>37){
   pendingSequence="";pendingSequenceFrames=0;
   setStatus("Лента изменилась, проверяю новый результат…");
   return;
  }
  if(current.equals(pendingSequence))pendingSequenceFrames++;else{pendingSequence=current;pendingSequenceFrames=1;}
  // Two matching OCR frames are enough to capture the update before the ticker shifts again.
  if(pendingSequenceFrames>=2){
   String coefficient=index<coeffs.size()?coeffs.get(index):"-";
   recordAutomatic(candidate,coefficient,current,coeffCsv);pendingSequence="";pendingSequenceFrames=0;
  }
 }
 private int inferNewTickerIndex(ArrayList<Integer> current,ArrayList<Integer> old){
  if(current==null||old==null||current.size()<5||old.size()<5)return -1;
  int n=current.size(),m=old.size();
  int[][] dp=new int[n+1][m+1];
  for(int i=1;i<=n;i++)for(int j=1;j<=m;j++)
   dp[i][j]=current.get(i-1).equals(old.get(j-1))?dp[i-1][j-1]+1:Math.max(dp[i-1][j],dp[i][j-1]);
  int minimum=Math.min(4,Math.min(n,m)-1);
  if(dp[n][m]<minimum)return -1;
  ArrayList<Integer> unmatched=new ArrayList<>();
  int i=n,j=m;
  while(i>0&&j>0){
   if(current.get(i-1).equals(old.get(j-1))){i--;j--;}
   else if(dp[i-1][j]>=dp[i][j-1]){unmatched.add(i-1);i--;}
   else j--;
  }
  while(i>0){unmatched.add(i-1);i--;}
  if(unmatched.size()!=1)return -1;
  int idx=unmatched.get(0);
  // A new round enters only at an end of the visible ticker, not in its middle.
  return (idx==0||idx==n-1)?idx:-1;
 }
 private Integer parseResultToken(String raw){
  Matcher m=RESULT_TOKEN.matcher(raw);if(!m.find())return null;
  try{return m.group(1)==null?37:Integer.parseInt(m.group(1));}catch(Exception ignored){return null;}
 }
 private Integer parseCellLabel(String raw){
  String t=raw==null?"":raw.trim();
  if(!t.matches("(?:[1-9]|[12][0-9]|3[0-6])"))return null;
  try{return Integer.parseInt(t);}catch(Exception ignored){return null;}
 }
 private String parseCoefficient(String raw){
  Matcher m=COEFFICIENT_TOKEN.matcher(raw==null?"":raw);if(!m.find())return null;return m.group(1).replace(',','.');
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
  android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
  String savedCoefficient=(coefficient==null||coefficient.isEmpty())?"-":coefficient;
  // SQLite stores every round and its 36-cell snapshot without a fixed 500-record cap.
  String boardSnapshot=(latestBoardCount>=30 && System.currentTimeMillis()-latestBoardAt<=5000L)?latestBoardCsv:"";
  store.addRound(n,savedCoefficient,boardSnapshot);
  long total=store.getRoundCount();
  p.edit().putString("tickerSequence",ticker).putString("tickerCoefficients",tickerCoefficients)
   .putInt("rounds",(int)Math.min(Integer.MAX_VALUE,total)).apply();
  
  setStatus("Записан раунд №"+total+": "+n+" • коэффициент "+savedCoefficient);
  refreshPanel();broadcastUpdate(n);
 }
 private void setStatus(String value){if(statusView!=null)statusView.setText(value);}
 private void broadcastUpdate(int n){Intent i=new Intent(ACTION_NUMBER);i.setPackage(getPackageName());i.putExtra("number",n);i.putExtra("automatic",true);sendBroadcast(i);}
 private String joinNumbers(List<Integer>a){StringBuilder b=new StringBuilder();for(int n:a){if(b.length()>0)b.append(",");b.append(n);}return b.toString();}
 private String joinStrings(List<String>a){StringBuilder b=new StringBuilder();for(String s:a){if(b.length()>0)b.append(",");b.append(s);}return b.toString();}
 private String alignCoefficients(List<String>a,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++){if(i>0)b.append(",");b.append(i<a.size()?a.get(i):"-");}return b.toString();}
 private ArrayList<Integer> parseNumbers(String s){ArrayList<Integer>a=new ArrayList<>();for(String v:s.split(","))try{int n=Integer.parseInt(v.trim());if(n>=1&&n<=37)a.add(n);}catch(Exception ignored){}return a;}
 @Override public void onDestroy(){if(panel!=null&&windowManager!=null)try{windowManager.removeView(panel);}catch(Exception ignored){}if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();if(recognizer!=null)recognizer.close();if(store!=null)store.close();if(wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}stopForeground(true);super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
