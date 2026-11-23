package com.ringoai.sixone;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import android.media.projection.MediaProjectionManager;
import android.provider.Settings;
import android.net.Uri;
import java.util.*;

public class MainActivity extends Activity {
  private static final int CAPTURE_REQUEST=620, ARROW=37, TOTAL=37;
  private android.content.SharedPreferences prefs;
  private final ArrayList<Integer> history=new ArrayList<>();
  private final LinkedHashSet<Integer> selected=new LinkedHashSet<>();
  private final ArrayList<CheckBox> choices=new ArrayList<>();
  private TextView balanceView,statsView,statusView,historyView,adviceView;
  private EditText stakeInput,resultInput;
  private int balance,wins,losses,rounds,pendingStake;
  private final LinkedHashSet<Integer> pending=new LinkedHashSet<>();
  private BroadcastReceiver receiver;

  @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("ringo70",0);
    loadSaved();
    build(); registerOcr(); refresh();
  }
  private void loadSaved(){
    balance=prefs.getInt("balance",1000);
    wins=prefs.getInt("wins",0); losses=prefs.getInt("losses",0);
    rounds=prefs.getInt("rounds",0); pendingStake=prefs.getInt("pendingStake",0);
    pending.clear();
    for(String s:prefs.getString("pending","").split(","))try{if(!s.isEmpty())pending.add(Integer.parseInt(s));}catch(Exception ignored){}
    history.clear();
    for(String s:prefs.getString("history","").split(","))try{int n=Integer.parseInt(s);if(n>=1&&n<=TOTAL)history.add(n);}catch(Exception ignored){}
  }
  @Override protected void onResume(){
    super.onResume();
    if(prefs!=null){loadSaved();if(balanceView!=null)refresh();}
  }
  private TextView txt(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,7,0,7);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
  private Button btn(String s){Button b=new Button(this);b.setText(s);return b;}
  private void gap(LinearLayout l,int h){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,h));}
  private void build(){
    ScrollView sc=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(22,18,22,30);root.setBackgroundColor(0xfff3f5f9);sc.addView(root);
    root.addView(txt("RINGO AI 7.0",28,true));root.addView(txt("36 ЧИСЕЛ + 1 ОБЩАЯ КЛЕТКА СО СТРЕЛКАМИ",13,true));
    balanceView=txt("",22,true);balanceView.setTextColor(0xff167447);root.addView(balanceView);
    root.addView(txt("Выбери несколько вариантов для виртуальной ставки",18,true));
    LinearLayout grid=new LinearLayout(this);grid.setOrientation(1);root.addView(grid);
    for(int row=0;row<9;row++){LinearLayout line=new LinearLayout(this);line.setOrientation(0);grid.addView(line);
      for(int col=1;col<=4;col++){int n=row*4+col;if(n>36)break;addChoice(line,n,String.valueOf(n),1);}}
    LinearLayout arrowLine=new LinearLayout(this);arrowLine.setOrientation(0);grid.addView(arrowLine);addChoice(arrowLine,ARROW,"🟢 ↔ 🔴  СТРЕЛКИ (ОДНА КЛЕТКА)",2);
    root.addView(txt("Сумма на каждый выбранный вариант",15,true));stakeInput=new EditText(this);stakeInput.setInputType(2);stakeInput.setText("10");stakeInput.setSingleLine(true);root.addView(stakeInput);
    Button bet=btn("Поставить виртуально на выбранные варианты");root.addView(bet);bet.setOnClickListener(v->placeBet());
    root.addView(txt("Результат раунда (1–36 или стрелки)",17,true));resultInput=new EditText(this);resultInput.setSingleLine(true);resultInput.setHint("Введи 1–36; для стрелок введи 37");resultInput.setInputType(2);root.addView(resultInput);
    Button result=btn("Записать результат раунда");root.addView(result);result.setOnClickListener(v->record());
    Button start=btn("Включить распознавание экрана");root.addView(start);start.setOnClickListener(v->startReader());
    Button stop=btn("Остановить распознавание");root.addView(stop);stop.setOnClickListener(v->{Intent i=new Intent(this,ScreenOcrService.class);i.setAction(ScreenOcrService.ACTION_STOP);startService(i);});
    statusView=txt("Готов к работе в демо-режиме",15,true);root.addView(statusView);
    root.addView(txt("АНАЛИТИКА",20,true));statsView=txt("",15,false);root.addView(statsView);adviceView=txt("",14,false);root.addView(adviceView);
    root.addView(txt("ИСТОРИЯ",18,true));historyView=txt("",14,false);root.addView(historyView);
    Button reset=btn("Сбросить демо-данные");root.addView(reset);reset.setOnClickListener(v->{balance=1000;wins=losses=rounds=pendingStake=0;pending.clear();history.clear();selected.clear();for(CheckBox c:choices)c.setChecked(false);save();refresh();statusView.setText("Демо-данные сброшены.");});
    root.addView(txt("Только виртуальные ставки. Прогноз не гарантирует выигрыш. Распознавание чисел экспериментальное; клетка стрелок распознаётся как отдельный результат только после проверки человеком.",12,false));
    setContentView(sc);
  }
  private void addChoice(LinearLayout line,int n,String label,int weight){CheckBox c=new CheckBox(this);c.setText(label);c.setTextSize(13);c.setPadding(5,4,5,4);c.setOnCheckedChangeListener((b,on)->{if(on)selected.add(n);else selected.remove(n);});line.addView(c,new LinearLayout.LayoutParams(0,-2,weight));choices.add(c);}
  private void placeBet(){if(!pending.isEmpty()){toast("Сначала запиши исход предыдущего раунда.");return;}if(selected.isEmpty()){toast("Выбери хотя бы один вариант.");return;}int stake;try{stake=Integer.parseInt(stakeInput.getText().toString());}catch(Exception e){toast("Проверь сумму.");return;}long total=(long)stake*selected.size();if(stake<=0||total>balance){toast("Недостаточно виртуального баланса.");return;}pending.addAll(selected);pendingStake=stake;balance-=total;save();statusView.setText("Виртуально выбрано: "+labels(pending)+". Общая сумма: "+total);refresh();}
  private String labels(Collection<Integer> values){ArrayList<String>a=new ArrayList<>();for(int n:values)a.add(n==ARROW?"Стрелки":String.valueOf(n));return android.text.TextUtils.join(", ",a);}
  private void record(){int n;try{n=Integer.parseInt(resultInput.getText().toString().trim());}catch(Exception e){toast("Введи число 1–36 или 37 для клетки стрелок.");return;}if(n<1||n>TOTAL){toast("Допустимы 1–36 и 37 (общая клетка стрелок).");return;}rounds++;history.add(0,n);if(history.size()>500)history.remove(history.size()-1);
    if(!pending.isEmpty()){if(pending.contains(n)){int payout=(int)Math.round(pendingStake*5.2);balance+=payout;wins++;statusView.setText("Совпадение в демо-режиме. Условное начисление: "+payout);}else{losses++;statusView.setText("Не совпало. Виртуальная ставка проиграна.");}pending.clear();pendingStake=0;}else statusView.setText("Результат сохранён.");
    resultInput.setText("");save();refresh();}
  private void registerOcr(){receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){int n=i.getIntExtra("number",-1);if(n>=1&&n<=36){resultInput.setText(String.valueOf(n));statusView.setText("OCR обнаружил "+n+" — проверь и нажми «Записать результат раунда».");}}};IntentFilter f=new IntentFilter(ScreenOcrService.ACTION_NUMBER);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);}
  private void startReader(){if(!Settings.canDrawOverlays(this)){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);startActivityForResult(m.createScreenCaptureIntent(),CAPTURE_REQUEST);}
  @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==CAPTURE_REQUEST&&c==RESULT_OK&&d!=null){Intent s=new Intent(this,ScreenOcrService.class);s.putExtra("resultCode",c);s.putExtra("data",d);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);}}
  @Override protected void onDestroy(){if(receiver!=null)try{unregisterReceiver(receiver);}catch(Exception e){}super.onDestroy();}
  private void refresh(){
    balanceView.setText("Демо-баланс: "+balance);
    int settled=wins+losses;
    statsView.setText("Раундов: "+rounds+"\nВыигранных виртуальных прогнозов: "+wins+
      "\nПроигранных: "+losses+"\nТочность демо-ставок: "+
      (settled==0?"—":String.format(Locale.US,"%.1f%%",wins*100.0/settled))+
      "\nАктивный выбор: "+(pending.isEmpty()?"нет":labels(pending))+
      "\nДемо-коэффициент выплаты: 5.2x");
    adviceView.setText(RingoBrain.buildInsight(history));
    StringBuilder h=new StringBuilder();
    for(int i=0;i<Math.min(80,history.size());i++)
      h.append("#").append(Math.max(1,rounds-i)).append(": ")
       .append(history.get(i)==37?"СТРЕЛКИ":history.get(i)).append("\n");
    historyView.setText(h.length()==0?"История пока пуста":h.toString());
  }
  private void save(){StringBuilder h=new StringBuilder(),p=new StringBuilder();for(int n:history){if(h.length()>0)h.append(",");h.append(n);}for(int n:pending){if(p.length()>0)p.append(",");p.append(n);}prefs.edit().putInt("balance",balance).putInt("wins",wins).putInt("losses",losses).putInt("rounds",rounds).putInt("pendingStake",pendingStake).putString("pending",p.toString()).putString("history",h.toString()).apply();}
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
