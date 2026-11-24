package com.ringoai.sixone;
import android.app.*;import android.os.*;import android.content.*;import android.graphics.Typeface;import android.widget.*;
import android.media.projection.MediaProjectionManager;import android.provider.Settings;import android.net.Uri;import java.util.*;

public class MainActivity extends Activity {
 private static final int CAPTURE_REQUEST=620;private android.content.SharedPreferences prefs;
 private TextView statusView,recommendationView,historyView,statsView,boardView;private BroadcastReceiver receiver;
 @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("ringo70",0);build();registerUpdates();refresh();}
 private TextView text(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,7,0,7);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
 private Button button(String s){Button b=new Button(this);b.setText(s);return b;}
 private void build(){
  ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(22,18,22,28);root.setBackgroundColor(0xfff3f5f9);scroll.addView(root);
  root.addView(text("RINGO AI 7.4",26,true));root.addView(text("АВТОМАТИЧЕСКИЙ НАБЛЮДАТЕЛЬ ИГРЫ",13,true));
  root.addView(text("Автоматически считывает видимые 36 ячеек игрового поля и верхнюю ленту результатов, связывает номера с коэффициентами и сохраняет наблюдения в памяти телефона. Панель может оставаться поверх других приложений.",15,false));
  Button start=button("ЗАПУСТИТЬ НАБЛЮДЕНИЕ");root.addView(start);start.setOnClickListener(v->startReader());
  Button stop=button("ОСТАНОВИТЬ НАБЛЮДЕНИЕ");root.addView(stop);stop.setOnClickListener(v->{Intent i=new Intent(this,ScreenOcrService.class);i.setAction(ScreenOcrService.ACTION_STOP);startService(i);statusView.setText("Наблюдение остановлено.");});
  statusView=text("Готов. Нажми «Запустить наблюдение».",14,true);root.addView(statusView);
  root.addView(text("ПОДСКАЗКА НА СЛЕДУЮЩИЙ РАУНД",18,true));recommendationView=text("Пока нет истории.",20,true);root.addView(recommendationView);
  root.addView(text("СТАТИСТИКА",18,true));statsView=text("",14,false);root.addView(statsView);
  root.addView(text("СНИМОК 36 ЯЧЕЕК И КОЭФФИЦИЕНТЫ",18,true));boardView=text("",12,false);root.addView(boardView);
  root.addView(text("ИСТОРИЯ РЕЗУЛЬТАТОВ И КОЭФФИЦИЕНТОВ",18,true));historyView=text("",14,false);root.addView(historyView);
  root.addView(text("Наблюдение продолжается через другие приложения, но Android показывает только экран перед глазами: когда игра скрыта за WhatsApp или Instagram, её данные не видны до возвращения в игру. Бот не нажимает игровые кнопки и не делает ставок. Статистика не гарантирует следующий исход.",12,false));
  setContentView(scroll);
 }
 private void startReader(){
  if(!Settings.canDrawOverlays(this)){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));statusView.setText("Разреши показ поверх других приложений, вернись сюда и нажми запуск.");return;}
  MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);startActivityForResult(m.createScreenCaptureIntent(),CAPTURE_REQUEST);
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==CAPTURE_REQUEST&&result==RESULT_OK&&data!=null){Intent s=new Intent(this,ScreenOcrService.class);s.putExtra("resultCode",result);s.putExtra("data",data);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);statusView.setText("Наблюдение включено. Результаты будут записываться автоматически.");}else if(request==CAPTURE_REQUEST)statusView.setText("Разрешение на захват экрана не выдано.");}
 private void registerUpdates(){receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh();int n=i.getIntExtra("number",-1);if(n>=1&&n<=36)statusView.setText("Автоматически записан новый исход: "+n);}};IntentFilter f=new IntentFilter(ScreenOcrService.ACTION_NUMBER);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);}
 @Override protected void onResume(){super.onResume();if(prefs!=null)refresh();}
 private ArrayList<Integer> readHistory(){ArrayList<Integer>a=new ArrayList<>();for(String s:prefs.getString("history","").split(","))try{int n=Integer.parseInt(s.trim());if(n>=1&&n<=37)a.add(n);}catch(Exception ignored){}return a;}
 private ArrayList<String> readCoefficients(){ArrayList<String>a=new ArrayList<>();for(String s:prefs.getString("historyCoefficients","").split(",",-1))if(!s.trim().isEmpty())a.add(s.trim());return a;}
 private void refresh(){
  ArrayList<Integer>n=readHistory();ArrayList<String>c=readCoefficients();
  String board=prefs.getString("boardLatest","");String samples=prefs.getString("boardSamples","");
  recommendationView.setText(RingoBrain.buildRecommendation(n,board,samples));
  int cells=prefs.getInt("boardCells",0);int sampleCount=samples.isEmpty()?0:samples.split("\\n").length;
  statsView.setText("Записано результатов: "+n.size()+"\nКоэффициентов ленты: "+c.size()+
   "\nЯчеек считано в последнем кадре: "+cells+"/36\nСнимков поля сохранено: "+sampleCount+
   "\nХранение: память этого приложения");
  if(board.isEmpty())boardView.setText("Поле пока не распознано.");
  else{
   String[] values=board.split(",",-1);StringBuilder matrix=new StringBuilder();
   for(int i=0;i<36;i++){
    if(i>0&&i%3==0)matrix.append("\n");else if(i>0)matrix.append("   |   ");
    String v=i<values.length?values[i]:"-";
    matrix.append(i+1).append(": ").append("-".equals(v)?"нет данных":"x"+v);
   }
   boardView.setText(matrix.toString());
  }
  StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(30,n.size());i++){if(i>0)b.append("\n");b.append(i+1).append(". ").append(n.get(i)==37?"СТРЕЛКИ":n.get(i));if(i<c.size()&&!"-".equals(c.get(i)))b.append("  •  x").append(c.get(i));}
  historyView.setText(b.length()==0?"История пока пуста. Открой игру и включи наблюдение.":b.toString());
 }
 @Override protected void onDestroy(){if(receiver!=null)try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
}
