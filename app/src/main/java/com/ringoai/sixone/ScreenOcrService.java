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
    private EditText stakeInput, resultInput;
    private Button confirmButton;
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
                .setContentTitle("RINGO AI 7.1 — виртуальный режим")
                .setContentText("Плавающая панель ставок поверх игры. Только демо-баланс.")
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
            TextView header = label("RINGO AI 7.1  ⋮  перетащи панель",15,true);
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
            detectedText=label("Распознанный исход появится здесь",12,false); panel.addView(detectedText);
            resultInput=new EditText(this); resultInput.setSingleLine(true); resultInput.setInputType(2);
            resultInput.setTextColor(Color.WHITE); resultInput.setHintTextColor(0xffcccccc);
            resultInput.setHint("Исход: 1–36 или 37 для стрелок"); panel.addView(resultInput);
            confirmButton=action("ПОДТВЕРДИТЬ ИСХОД И ЗАКРЫТЬ СТАВКУ"); panel.addView(confirmButton);
            confirmButton.setOnClickListener(v->confirmOutcome());
            resultText=label("Виртуальный режим. Реальных ставок нет.",11,false); panel.addView(resultText);
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
    private void confirmOutcome(){
        int n;try{n=Integer.parseInt(resultInput.getText().toString().trim());}catch(Exception e){resultText.setText("Введи исход 1–36 или 37 (стрелки).");return;}
        if(n<1||n>37){resultText.setText("Допустимы числа 1–36 и 37 для стрелок.");return;}
        rounds++;
        if(hasPending){
            if(pending.contains(n)){int payout=(int)Math.round(stakePerChoice*5.2);balance+=payout;wins++;resultText.setText("ВИРТУАЛЬНОЕ ПОПАДАНИЕ! Начислено "+payout+" демо-единиц.");}
            else {losses++;resultText.setText("Мимо. Виртуальная ставка проиграна.");}
            pending.clear();hasPending=false;stakePerChoice=0;
        } else resultText.setText("Исход записан в статистику.");
        android.content.SharedPreferences p=getSharedPreferences("ringo70",0);
        String old=p.getString("history","");String updated=n+(old.isEmpty()?"":","+old);
        String[] parts=updated.split(",");if(parts.length>500){StringBuilder sb=new StringBuilder();for(int i=0;i<500;i++){if(i>0)sb.append(",");sb.append(parts[i]);}updated=sb.toString();}
        p.edit().putString("history",updated).apply();saveState();refreshPanel();
        resultInput.setText("");detectedText.setText("Исход подтверждён: "+(n==37?"СТРЕЛКИ":n));
    }

    private void startCapture() {
        int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels,density=getResources().getDisplayMetrics().densityDpi;
        frameWidth=width; frameHeight=height;
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(source->{
            if(!processing.compareAndSet(false,true)){Image skipped=null;try{skipped=source.acquireLatestImage();}catch(Exception ignored){}if(skipped!=null)skipped.close();return;}
            Image image=null;
            try{
                image=source.acquireLatestImage();if(image==null){processing.set(false);return;}
                Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
                int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride(),rowPadding=rowStride-pixelStride*width;
                Bitmap bitmap=Bitmap.createBitmap(width+rowPadding/pixelStride,height,Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);Bitmap cropped=Bitmap.createBitmap(bitmap,0,0,width,height);bitmap.recycle();
                image.close();image=null;
                recognizer.process(InputImage.fromBitmap(cropped,0)).addOnSuccessListener(this::handleText)
                    .addOnFailureListener(e->{}).addOnCompleteListener(t->{cropped.recycle();processing.set(false);});
            }catch(Exception e){if(image!=null)image.close();processing.set(false);}
        },new Handler(Looper.getMainLooper()));
        display=projection.createVirtualDisplay("RingoOCR",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
    }
    private void handleText(Text text){
        int candidate=-1;
        double bestScore=Double.NEGATIVE_INFINITY;
        for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
            String value=line.getText().trim();
            if(!value.matches("[1-9]|[12][0-9]|3[0-6]"))continue;
            android.graphics.Rect bounds=line.getBoundingBox();
            if(bounds==null||bounds.height()<dp(16))continue;
            // Ignore text rendered inside our own floating panel, when the OS includes it in capture.
            if(panel!=null){
                int left=paramsX, top=paramsY, right=left+dp(325), bottom=top+panel.getHeight();
                if(bounds.left<right&&bounds.right>left&&bounds.top<bottom&&bounds.bottom>top)continue;
            }
            float cx=(bounds.left+bounds.right)/2f, cy=(bounds.top+bounds.bottom)/2f;
            if(frameWidth>0&&(cx<frameWidth*0.08f||cx>frameWidth*0.92f))continue;
            if(frameHeight>0&&(cy<frameHeight*0.10f||cy>frameHeight*0.90f))continue;
            double centerX=frameWidth>0?Math.abs(cx-frameWidth/2.0)/frameWidth:0.5;
            double centerY=frameHeight>0?Math.abs(cy-frameHeight/2.0)/frameHeight:0.5;
            double score=bounds.height()*(1.0-0.55*centerX-0.35*centerY);
            if(score>bestScore){try{candidate=Integer.parseInt(value);bestScore=score;}catch(Exception ignored){}}
        }
        if(candidate<0){emptyCount++;if(emptyCount>=5){stableCandidate=-1;stableCount=0;lastSent=-1;}return;}
        emptyCount=0;
        if(candidate==stableCandidate)stableCount++;else{stableCandidate=candidate;stableCount=1;}
        // Require repeated stable recognition. It is still a candidate: the player confirms it before saving.
        if(stableCount>=5&&candidate!=lastSent){lastSent=candidate;showDetected(candidate);}
    }
    private void showDetected(int n){
        if(panel!=null){detectedText.setText("OCR увидел: "+n+" — проверь число и нажми подтверждение.");resultInput.setText(String.valueOf(n));}
        Intent broadcast=new Intent(ACTION_NUMBER);broadcast.setPackage(getPackageName());broadcast.putExtra("number",n);sendBroadcast(broadcast);
    }
    @Override public void onDestroy(){
        if(panel!=null&&windowManager!=null){try{windowManager.removeView(panel);}catch(Exception ignored){}}
        if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();
        if(recognizer!=null)recognizer.close();stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}