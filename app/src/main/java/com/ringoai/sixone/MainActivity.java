package com.ringoai.sixone;
import android.app.*;import android.os.*;import android.content.*;import android.graphics.Typeface;import android.widget.*;
import android.media.projection.MediaProjectionManager;import android.provider.Settings;import android.net.Uri;import java.util.*;

public class MainActivity extends Activity {
 private static final int CAPTURE_REQUEST=620;private android.content.SharedPreferences prefs;
 private TextView statusView,recommendationView,historyView,statsView;private BroadcastReceiver receiver;
 @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("ringo70",0);build();registerUpdates();refresh();}
 private TextView text(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,7,0,7);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
 private Button button(String s){Button b=new Button(this);b.setText(s);return b;}
 private void build(){
  ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(22,18,22,28);root.setBackgroundColor(0xfff3f5f9);scroll.addView(root);
  root.addView(text("RINGO AI 7.4",26,true));root.addView(text("АВТОМАТИЧЕСКИЙ НАБЛЮДАТЕЛЬ ИГРЫ",13,true));
  root.addView(text("Сам читает строку последних результатов, записывает числа вместе с коэффициентами в память приложения, анализирует историю и показывает одну подсказку поверх игры. Вводить числа или подтверждать каждый раунд не нужно.",15,false));
  Button start=button("ЗАПУСТИТЬ НАБЛЮДЕНИЕ");root.addView(start);start.setOnClickListener(v->startReader());
  Button stop=button("ОСТАНОВИТЬ НАБЛЮДЕНИЕ");root.addView(stop);stop.setOnClickListener(v->{Intent i=new Intent(this,ScreenOcrService.class);i.setAction(ScreenOcrService.ACTION_STOP);startService(i);statusView.setText("Наблюдение остановлено.");});
  statusView=text("Готов. Нажми «Запустить наблюдение».",14,true);root.addView(statusView);
  root.addView(text("ПОДСКАЗКА НА СЛЕДУЮЩИЙ РАУНД",18,true));recommendationView=text("Пока нет истории.",20,true);root.addView(recommendationView);
  root.addView(text("СТАТИСТИКА",18,true));statsView=text("",14,false);root.addView(statsView);
  root.addView(text("ИСТОРИЯ РЕЗУЛЬТАТОВ И КОЭФФИЦИЕНТОВ",18,true));historyView=text("",14,false);root.addView(historyView);
  root.addView(text("Бот только подсказывает, что выбрать в игре; сам не нажимает игровые кнопки и не делает реальных ставок. Прогноз не гарантирует исход случайной игры.",12,false));
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
  ArrayList<Integer>n=readHistory();ArrayList<String>c=readCoefficients();recommendationView.setText(RingoBrain.buildRecommendation(n));
  statsView.setText("Записано результатов: "+n.size()+"\nКоэффициентов сохранено: "+c.size()+"\nПоследний коэффициент: "+(c.isEmpty()?"—":c.get(0))+"\nХранение: память этого приложения");
  StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(30,n.size());i++){if(i>0)b.append("\n");b.append(i+1).append(". ").append(n.get(i)==37?"СТРЕЛКИ":n.get(i));if(i<c.size()&&!"-".equals(c.get(i)))b.append("  •  x").append(c.get(i));}
  historyView.setText(b.length()==0?"История пока пуста. Открой игру и включи наблюдение.":b.toString());
 }
 @Override protected void onDestroy(){if(receiver!=null)try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
}
