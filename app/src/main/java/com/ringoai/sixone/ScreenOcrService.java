package com.ringoai.sixone;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenOcrService extends Service {
    public static final String ACTION_NUMBER = "com.ringoai.sixone.OCR_NUMBER";
    public static final String ACTION_STOP = "com.ringoai.sixone.OCR_STOP";
    private static final String CHANNEL = "ringo_screen_reader";
    private static final int ARROW = 37;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextRecognizer recognizer;
    private WindowManager windowManager;
    private LinearLayout panel;
    private TextView balanceText, selectedText, detectedText, resultText, intelligenceView;
    private int frameWidth, frameHeight;
    private EditText stakeInput;
    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?<![0-9])([1-9]|[12][0-9]|3[0-6])(?![0-9])");
    private long lastScanAt = 0L;
    private String candidateSequence = "";
    private int candidateSequenceCount = 0;
    private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();
    private final LinkedHashSet<Integer> pending = new LinkedHashSet<>();
    private final ArrayList<Button> numberButtons = new ArrayList<>();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private int stableCandidate = -1, stableCount = 0, emptyCount = 0, lastSent = -1;
    private int balance = 1000, stakePerChoice = 10, rounds = 0, wins = 0, losses = 0;
    private boolean hasPending = false;

    @Override public void onCreate() {
        super.onCreate();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        loadState();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "RINGO AI — чтение экрана", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (intent == null || !intent.hasExtra("data")) { stopSelf(); return START_NOT_STICKY; }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("RINGO AI 7.3 — автомониторинг")
                .setContentText("Автоматически отслеживает новые результаты. Только демо-баланс.")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(620, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(620, notification);
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));
        showFloatingPanel();
        startCapture();
        return START_NOT_STICKY;
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(12)); return d;
    }
    private TextView label(String value, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(Color.WHITE); t.setPadding(dp(5),dp(4),dp(5),dp(4));
        if (bold) t.setTypeface(null, Typeface.BOLD); return t;
    }
    private Button action(String value) {
        Button b = new Button(this); b.setText(value); b.setTextSize(12);
        b.setAllCaps(false); return b;
    }
    private void showFloatingPanel() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
            windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
            panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(10),dp(8),dp(10),dp(8)); panel.setBackground(shape(0xEE172337));
            panel.setElevation(dp(10));
            TextView header = label("RINGO AI 7.3  ⋮  перетащи панель",15,true);
            panel.addView(header);
            header.setOnTouchListener(new View.OnTouchListener() {
                float sx, sy; int ox, oy;
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    if (e.getAction()==MotionEvent.ACTION_DOWN) { sx=e.getRawX(); sy=e.getRawY(); ox=paramsX; oy=paramsY; return true; }
                    if (e.getAction()==MotionEvent.ACTION_MOVE) { paramsX=ox+(int)(e.getRawX()-sx); paramsY=oy+(int)(e.getRawY()-sy); updatePosition(); return true; }
                    return true;
                }
            });
            balanceText=label("",14,true); panel.addView(balanceText);
            intelligenceView=label("ИИ готовит статистическую оценку...",11,false); panel.addView(intelligenceView);
            selectedText=label("Выбрано: ничего",12,false); panel.addView(selectedText);
            ScrollView scroll = new ScrollView(this);
            LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
            for (int r=0;r<6;r++) {
                LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c=1;c<=6;c++) {
                    final int n=r*6+c; Button b=action(String.valueOf(n));
                    b.setPadding(0,0,0,0); b.setMinWidth(0); b.setMinimumWidth(0);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(38),1); lp.setMargins(dp(2),dp(2),dp(2),dp(2));
                    row.addView(b,lp); numberButtons.add(b);
                    b.setOnClickListener(v->{ if(selected.contains(n)) selected.remove(n); else selected.add(n); refreshPanel(); });
                }
                grid.addView(row);
            }
            scroll.addView(grid); panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(150)));
            Button arrow=action("🟢 ↔ 🔴  СТРЕЛКИ — одна общая клетка");
            panel.addView(arrow); arrow.setOnClickListener(v->{if(selected.contains(ARROW))selected.remove(ARROW);else selected.add(ARROW);refreshPanel();});
            LinearLayout stakeRow=new LinearLayout(this); stakeRow.setOrientation(LinearLayout.HORIZONTAL);
            stakeRow.addView(label("На каждый вариант:",12,false),new LinearLayout.LayoutParams(0,-2,1));
            stakeInput=new EditText(this); stakeInput.setSingleLine(true); stakeInput.setInputType(2);
            stakeInput.setText("10"); stakeInput.setTextColor(Color.WHITE); stakeInput.setHintTextColor(0xffcccccc);
            stakeInput.setHint("Сумма"); stakeRow.addView(stakeInput,new LinearLayout.LayoutParams(dp(85),dp(44)));
            panel.addView(stakeRow);
            Button bet=action("ПОСТАВИТЬ ВИРТУАЛЬНО"); panel.addView(bet);
            bet.setOnClickListener(v->placeVirtualBet());
            detectedText=label("Ожидаю новый результат в верхней строке игры...",12,false); panel.addView(detectedText);
            resultText=label("АВТОЗАПИСЬ ВКЛЮЧЕНА. Результаты сохраняются сами; нажимать подтверждение не нужно. Только виртуальный режим.",11,false); panel.addView(resultText);
            Button hide=action("Скрыть панель (чтение продолжится)"); panel.addView(hide);
            hide.setOnClickListener(v->{try{windowManager.removeView(panel);panel=null;}catch(Exception ignored){}});
            int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
            paramsX=dp(8); paramsY=dp(90);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(dp(320),WindowManager.LayoutParams.WRAP_CONTENT,type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.TOP|Gravity.START; lp.x=paramsX;lp.y=paramsY; windowManager.addView(panel,lp); layoutParams=lp;
            refreshPanel();
        } catch(Exception e) { panel=null; }
    }
    private int paramsX=0, paramsY=0;
    private WindowManager.LayoutParams layoutParams;
    private void updatePosition(){ if(panel!=null&&layoutParams!=null){layoutParams.x=paramsX;layoutParams.y=paramsY;try{windowManager.updateViewLayout(panel,layoutParams);}catch(Exception ignored){}} }
    private void loadState(){
        android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
        balance=p.getInt("balance",1000);wins=p.getInt("wins",0);losses=p.getInt("losses",0);rounds=p.getInt("rounds",0);
        String raw=p.getString("pending","");for(String s:raw.split(","))try{if(!s.isEmpty())pending.add(Integer.parseInt(s));}catch(Exception ignored){}
        stakePerChoice=p.getInt("pendingStake",10);hasPending=!pending.isEmpty();
    }
    private void saveState(){
        StringBuilder b=new StringBuilder();for(int n:pending){if(b.length()>0)b.append(",");b.append(n);}
        getSharedPreferences("ringo70",0).edit().putInt("balance",balance).putInt("wins",wins).putInt("losses",losses)
          .putInt("rounds",rounds).putInt("pendingStake",hasPending?stakePerChoice:0).putString("pending",b.toString()).apply();
    }
    private String names(Collection<Integer> values){ArrayList<String>a=new ArrayList<>();for(int n:values)a.add(n==ARROW?"СТРЕЛКИ":String.valueOf(n));return android.text.TextUtils.join(", ",a);}
    private void refreshPanel(){
        if(panel==null)return;
        balanceText.setText("Виртуальный баланс: "+balance);
        selectedText.setText("Выбрано: "+(selected.isEmpty()?"ничего":names(selected))+(hasPending?"\nАктивная ставка: "+names(pending)+" × "+stakePerChoice:""));
        for(int i=0;i<numberButtons.size();i++){int n=i+1;numberButtons.get(i).setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected.contains(n)?0xff16865b:0xff394b63));}
        refreshInsight();
    }
    private void refreshInsight(){
        if(intelligenceView==null)return;
        android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
        ArrayList<Integer> data=new ArrayList<>();
        for(String s:p.getString("history","").split(","))try{
            int n=Integer.parseInt(s);if(n>=1&&n<=37)data.add(n);
        }catch(Exception ignored){}
        intelligenceView.setText(RingoBrain.buildInsight(data));
    }
    private void placeVirtualBet(){
        if(hasPending){resultText.setText("Сначала подтверди исход предыдущего раунда.");return;}
        if(selected.isEmpty()){resultText.setText("Сначала выбери числа или стрелки.");return;}
        int stake;try{stake=Integer.parseInt(stakeInput.getText().toString());}catch(Exception e){resultText.setText("Проверь сумму ставки.");return;}
        long total=(long)stake*selected.size();if(stake<=0||total>balance){resultText.setText("Недостаточно виртуального баланса.");return;}
        pending.clear();pending.addAll(selected);stakePerChoice=stake;balance-=(int)total;hasPending=true;saveState();refreshPanel();
        resultText.setText("Виртуальная ставка принята: "+names(pending)+". Списано: "+total);
    }
    private void recordAutomatic(int n, String sequence) {
        android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
        String old=p.getString("history","");
        String updated=n+(old.isEmpty()?"":","+old);
        String[] parts=updated.split(",");
        if(parts.length>500){StringBuilder sb=new StringBuilder();for(int i=0;i<500;i++){if(i>0)sb.append(",");sb.append(parts[i]);}updated=sb.toString();}
        rounds=p.getInt("rounds",0)+1;
        balance=p.getInt("balance",1000);
        wins=p.getInt("wins",0); losses=p.getInt("losses",0);
        String rawPending=p.getString("pending","");
        pending.clear();
        for(String s:rawPending.split(","))try{if(!s.isEmpty())pending.add(Integer.parseInt(s));}catch(Exception ignored){}
        hasPending=!pending.isEmpty();
        stakePerChoice=p.getInt("pendingStake",10);
        String message="Автоматически записан новый результат: "+n;
        if(hasPending){
            if(pending.contains(n)){
                int payout=(int)Math.round(stakePerChoice*5.2);
                balance+=payout;wins++;
                message+=" — виртуальное попадание, начислено "+payout;
            }else{losses++;message+=" — виртуальная ставка не совпала";}
            pending.clear();hasPending=false;stakePerChoice=0;
        }
        p.edit().putString("history",updated).putString("tickerSequence",sequence)
          .putInt("rounds",rounds).putInt("balance",balance).putInt("wins",wins)
          .putInt("losses",losses).putInt("pendingStake",0).putString("pending","").apply();
        resultText.setText(message);
        detectedText.setText("НОВЫЙ ИСХОД ЗАПИСАН АВТОМАТИЧЕСКИ: "+n);
        saveState();refreshPanel();
        Intent broadcast=new Intent(ACTION_NUMBER);
        broadcast.setPackage(getPackageName());broadcast.putExtra("number",n);
        broadcast.putExtra("automatic",true);sendBroadcast(broadcast);
    }

    private void startCapture() {
        int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels,density=getResources().getDisplayMetrics().densityDpi;
        frameWidth=width; frameHeight=height;
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(source->{
            long now=SystemClock.elapsedRealtime();
            if(now-lastScanAt<650L){
                Image skipped=null;try{skipped=source.acquireLatestImage();}catch(Exception ignored){}
                if(skipped!=null)skipped.close();return;
            }
            if(!processing.compareAndSet(false,true)){
                Image skipped=null;try{skipped=source.acquireLatestImage();}catch(Exception ignored){}
                if(skipped!=null)skipped.close();return;
            }
            lastScanAt=now;
            Image image=null;
            try{
                image=source.acquireLatestImage();if(image==null){processing.set(false);return;}
                Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
                int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride(),rowPadding=rowStride-pixelStride*width;
                Bitmap bitmap=Bitmap.createBitmap(width+rowPadding/pixelStride,height,Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                Bitmap full=Bitmap.createBitmap(bitmap,0,0,width,height);bitmap.recycle();
                // In the supplied game screen, the recent-results ticker is directly below the browser bar.
                // Read only that narrow strip so the timer, 1–36 grid, prices and our overlay cannot be mistaken for results.
                int left=Math.max(0,(int)(width*0.045f));
                int top=Math.max(0,(int)(height*0.084f));
                int right=Math.min(width,(int)(width*0.99f));
                int bottom=Math.min(height,(int)(height*0.115f));
                Bitmap strip=Bitmap.createBitmap(full,left,top,Math.max(1,right-left),Math.max(1,bottom-top));
                full.recycle();
                Bitmap enlarged=Bitmap.createScaledBitmap(strip,strip.getWidth()*4,strip.getHeight()*4,true);
                strip.recycle();
                image.close();image=null;
                recognizer.process(InputImage.fromBitmap(enlarged,0)).addOnSuccessListener(this::handleTickerText)
                    .addOnFailureListener(e->{}).addOnCompleteListener(t->{enlarged.recycle();processing.set(false);});
            }catch(Exception e){if(image!=null)image.close();processing.set(false);}
        },new Handler(Looper.getMainLooper()));
        display=projection.createVirtualDisplay("RingoTickerReader",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
        if(resultText!=null)resultText.setText("Автомониторинг включён: отслеживаю верхнюю строку результатов игры.");
    }

    private void handleTickerText(Text text){
        ArrayList<Text.Line> lines=new ArrayList<>();
        for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
            android.graphics.Rect r=line.getBoundingBox();
            if(r!=null&&r.centerY()<=frameHeight*4*0.0195f) lines.add(line);
        }
        Collections.sort(lines,(a,b)->{
            android.graphics.Rect ra=a.getBoundingBox(), rb=b.getBoundingBox();
            return Integer.compare(ra.left,rb.left);
        });
        ArrayList<Integer> sequence=new ArrayList<>();
        for(Text.Line line:lines){
            Matcher matcher=NUMBER_TOKEN.matcher(line.getText());
            while(matcher.find()){
                try{sequence.add(Integer.parseInt(matcher.group(1)));}catch(Exception ignored){}
            }
        }
        if(sequence.size()<5||sequence.size()>10)return;
        String current=joinNumbers(sequence);
        android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
        String previous=p.getString("tickerSequence","");
        if(previous.isEmpty()){
            p.edit().putString("tickerSequence",current).apply();
            if(p.getString("history","").trim().isEmpty()){
                StringBuilder initial=new StringBuilder();
                for(int n:sequence){if(initial.length()>0)initial.append(",");initial.append(n);}
                p.edit().putString("history",initial.toString())
                  .putInt("rounds",p.getInt("rounds",0)+sequence.size()).apply();
                rounds=p.getInt("rounds",0)+sequence.size();
                if(resultText!=null)resultText.setText("Строка истории найдена. Загружено "+sequence.size()+" видимых прошлых результатов.");
            }else if(resultText!=null)resultText.setText("Строка истории найдена. Автоматически жду следующий раунд.");
            candidateSequence="";candidateSequenceCount=0;refreshPanel();return;
        }
        if(current.equals(previous)){candidateSequence="";candidateSequenceCount=0;return;}
        ArrayList<Integer> oldValues=parseNumbers(previous);
        int leftOverlap=0;
        while(leftOverlap<Math.min(sequence.size()-1,oldValues.size())&&sequence.get(leftOverlap+1).equals(oldValues.get(leftOverlap)))leftOverlap++;
        int rightOverlap=0;
        while(rightOverlap<Math.min(sequence.size(),oldValues.size()-1)&&sequence.get(rightOverlap).equals(oldValues.get(rightOverlap+1)))rightOverlap++;
        int candidate=-1;
        if(leftOverlap>=4&&leftOverlap>rightOverlap)candidate=sequence.get(0); // newest item is on the left
        else if(rightOverlap>=4&&rightOverlap>leftOverlap)candidate=sequence.get(sequence.size()-1); // newest item is on the right
        if(candidate<1||candidate>36){candidateSequence="";candidateSequenceCount=0;return;}
        if(current.equals(candidateSequence))candidateSequenceCount++;else{candidateSequence=current;candidateSequenceCount=1;}
        if(candidateSequenceCount>=3){
            recordAutomatic(candidate,current);
            candidateSequence="";candidateSequenceCount=0;
        }
    }

    private String joinNumbers(List<Integer> values){
        StringBuilder b=new StringBuilder();
        for(int n:values){if(b.length()>0)b.append(",");b.append(n);}
        return b.toString();
    }
    private ArrayList<Integer> parseNumbers(String value){
        ArrayList<Integer> out=new ArrayList<>();
        for(String s:value.split(","))try{int n=Integer.parseInt(s);if(n>=1&&n<=36)out.add(n);}catch(Exception ignored){}
        return out;
    }
    @Override public void onDestroy(){
        if(panel!=null&&windowManager!=null){try{windowManager.removeView(panel);}catch(Exception ignored){}}
        if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();
        if(recognizer!=null)recognizer.close();stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}