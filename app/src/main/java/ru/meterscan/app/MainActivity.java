package ru.meterscan.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private LinearLayout body, candidates;
    private EditText name, value;
    private Spinner kind, tariff, digitCount;
    private Button auto;
    private ImageView cropPreview;
    private volatile boolean disposed=false;
    private TextView status, raw;
    private CropView photo;
    private Button recognize, save, camera, gallery, rotate;
    private Uri pending;
    private String source = "", ocr = "";
    private boolean busy = false;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextRecognizer engine = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private TextView label(String s, int size) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setPadding(0,dp(8),0,dp(8)); body.addView(v); return v; }
    private Button button(String text, Runnable action) { Button b = new Button(this); b.setText(text); body.addView(b); b.setOnClickListener(v -> action.run()); return b; }
    private EditText field(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); body.addView(e); return e; }
    private Spinner spinner(String[] items) { Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items)); body.addView(s); return s; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20),dp(16),dp(20),dp(24)); scroll.addView(body); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)-> { v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom()); return i; });
        label("Мои показания",28); label("Фото → цифры → проверка",16);
        name = field("Название счётчика, например: Вода — кухня");
        kind = spinner(new String[]{"Вода · м³","Электричество · кВт·ч","Газ · м³"});
        tariff = spinner(new String[]{"Без тарифа","Т1","Т2","Т3"});
        label("Количество чёрных разрядов на счётчике",14);
        digitCount = spinner(new String[]{"4 цифры","5 цифр","6 цифр","7 цифр","8 цифр"}); digitCount.setSelection(1);
        camera = button("Сфотографировать",this::takePhoto);
        gallery = button("Выбрать фото",()-> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,2); });
        photo = new CropView(); body.addView(photo,new LinearLayout.LayoutParams(-1,dp(280)));
        label("После загрузки область ищется автоматически. Зелёная рамка показывает выбранное окошко. Если рамка ошиблась, выделите цифры пальцем.",14);
        auto = button("Найти показания автоматически",()->analyze(true));
        cropPreview = new ImageView(this); cropPreview.setAdjustViewBounds(true); body.addView(cropPreview,new LinearLayout.LayoutParams(-1,dp(100)));
        rotate = button("Повернуть фото на 90°",()-> { if(photo.bitmap!=null) { Matrix m=new Matrix(); m.postRotate(90); photo.setBitmap(Bitmap.createBitmap(photo.bitmap,0,0,photo.bitmap.getWidth(),photo.bitmap.getHeight(),m,true)); invalidateResult(); } });
        recognize = button("Распознать выделенную область",this::recognize);
        status = label("Добавьте фотографию счётчика",16);
        candidates = new LinearLayout(this); candidates.setOrientation(LinearLayout.VERTICAL); body.addView(candidates);
        value = field("Показание — выберите вариант или введите вручную"); value.setInputType(2);
        raw = label("",14); raw.setTextIsSelectable(true);
        save = button("Подтвердить и сохранить",this::saveReading);
        button("История и экспорт JSON",this::history);
        if(state!=null) {
            digitCount.setSelection(state.getInt("digits",1)); name.setText(state.getString("name","")); value.setText(state.getString("value","")); kind.setSelection(state.getInt("kind")); tariff.setSelection(state.getInt("tariff"));
            String p=state.getString("pending"); if(p!=null) pending=Uri.parse(p);
            source=state.getString("source",""); if(!source.isEmpty()) load(Uri.parse(source));
        }
    }
    @Override public void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putString("name",name.getText().toString()); b.putString("value",value.getText().toString()); b.putInt("digits",digitCount.getSelectedItemPosition()); b.putInt("kind",kind.getSelectedItemPosition()); b.putInt("tariff",tariff.getSelectedItemPosition()); b.putString("source",source); if(pending!=null)b.putString("pending",pending.toString()); }
    private void setBusy(boolean b) { busy=b; for(Button x:new Button[]{camera,gallery,rotate,recognize,save,auto})x.setEnabled(!b); photo.setEnabled(!b); digitCount.setEnabled(!b); }
    private void invalidateResult() { if(value==null)return; value.setText(""); ocr=""; raw.setText(""); candidates.removeAllViews(); status.setText("Область изменена. Нажмите «Распознать»."); }
    private void takePhoto() {
        try { File dir=new File(getCacheDir(),"camera"); dir.mkdirs(); File f=File.createTempFile("meter_",".jpg",dir); pending=FileProvider.getUriForFile(this,getPackageName()+".files",f);
            Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE); i.putExtra(MediaStore.EXTRA_OUTPUT,pending); i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION); i.setClipData(ClipData.newRawUri("photo",pending)); startActivityForResult(i,1);
        } catch(Exception e) { status.setText("Не удалось открыть камеру. Попробуйте выбрать фото."); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data); if(result!=RESULT_OK)return;
        if(request==1&&pending!=null)load(pending);
        if(request==2&&data!=null&&data.getData()!=null) { Uri uri=data.getData(); try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }catch(SecurityException ignored){} load(uri); }
    }
    private void load(Uri uri) {
        setBusy(true); invalidateResult(); status.setText("Открываю фото…");
        worker.execute(()-> { try {
            Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),uri),(decoder,info,s)-> { decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); int w=info.getSize().getWidth(),h=info.getSize().getHeight(); float scale=Math.min(1f,2400f/Math.max(w,h)); decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale))); });
            runOnUiThread(()-> { if(isDestroyed())return; source=uri.toString(); photo.setBitmap(bitmap); setBusy(false); status.setText("Ищу окошко показаний…"); analyze(true); });
        }catch(Exception e) { runOnUiThread(()-> { if(isDestroyed())return; setBusy(false); status.setText("Не удалось прочитать фото. Выберите другое изображение."); }); } });
    }
    private void recognize() { analyze(false); }
    private static class Guess {
        String value; int votes; MeterVision.Region region; Bitmap preview;
        Guess(String v,MeterVision.Region r,Bitmap p){value=v;region=r;preview=p;}
    }
    private void analyze(boolean automatic) {
        if(busy)return;
        if(photo.bitmap==null){status.setText("Сначала добавьте фотографию");return;}
        final Bitmap image=photo.bitmap;
        final Bitmap manual=automatic?null:photo.cropped();
        final int expected=digitCount.getSelectedItemPosition()+4;
        setBusy(true);invalidateResult();status.setText(automatic?"Ищу окошко показаний…":"Распознаю область…");
        worker.execute(()->{
            try {
                if(!org.opencv.android.OpenCVLoader.initLocal())throw new IllegalStateException();
                List<MeterVision.Region> regions=automatic?MeterVision.locate(image,expected):new ArrayList<>();
                if(automatic&&regions.isEmpty()) {
                    // Numeric text is a fallback only. The user must inspect its frame:
                    // serial numbers can also look like a reading.
                    Text full=com.google.android.gms.tasks.Tasks.await(engine.process(InputImage.fromBitmap(image,0)),20,TimeUnit.SECONDS);
                    for(Text.TextBlock block:full.getTextBlocks())for(Text.Line line:block.getLines()) {
                        String compact=line.getText().replaceAll("\\s+","");
                        if(!compact.matches("[0-9]{"+expected+"}([.,][0-9]+)?"))continue;
                        android.graphics.Rect box=line.getBoundingBox();if(box==null)continue;
                        int pad=Math.max(4,box.height()/5);
                        regions.add(new MeterVision.Region(new android.graphics.Rect(Math.max(0,box.left-pad),Math.max(0,box.top-pad),Math.min(image.getWidth(),box.right+pad),Math.min(image.getHeight(),box.bottom+pad)),0,0));
                    }
                    regions.sort((x,y)->Integer.compare(y.box.height(),x.box.height()));
                    if(regions.size()>4)regions=new ArrayList<>(regions.subList(0,4));
                }
                if(!automatic)regions.add(new MeterVision.Region(new android.graphics.Rect(0,0,manual.getWidth(),manual.getHeight()),0,0));
                Map<String,Guess> guesses=new LinkedHashMap<>();StringBuilder debug=new StringBuilder();
                MeterVision.Region first=regions.isEmpty()?null:regions.get(0);
                Bitmap firstPreview=null;
                long deadline=SystemClock.elapsedRealtime()+60000;
                for(MeterVision.Region region:regions) {
                    if(disposed||SystemClock.elapsedRealtime()>deadline)break;
                    Bitmap cut=MeterVision.crop(automatic?image:manual,region);
                    if(firstPreview==null)firstPreview=cut;
                    for(int band=0;band<2;band++) {
                        // A second pass excludes the previous drum digit visible at the top.
                        int top=band==0?0:(int)(cut.getHeight()*.25);
                        Bitmap bandImage=Bitmap.createBitmap(cut,0,top,cut.getWidth(),cut.getHeight()-top);
                        for(int mode=0;mode<3;mode++) {
                            if(disposed||SystemClock.elapsedRealtime()>deadline)break;
                            Bitmap prepared=MeterVision.variant(bandImage,mode);
                            Text result;
                            try {result=com.google.android.gms.tasks.Tasks.await(engine.process(InputImage.fromBitmap(prepared,0)),20,TimeUnit.SECONDS);}finally{prepared.recycle();}
                            debug.append(result.getText()).append("\n");Set<String> pass=new HashSet<>();
                            for(Text.TextBlock block:result.getTextBlocks())for(Text.Line line:block.getLines()) {
                                String digits=line.getText().replaceAll("\\s+","");
                                if(digits.matches("[0-9]{"+expected+"}([.,][0-9]+)?"))pass.add(integerPart(digits));
                            }
                            for(String number:pass){String key=number+"@"+region.box.toShortString();Guess guess=guesses.get(key);if(guess==null){guess=new Guess(number,region,cut);guesses.put(key,guess);}guess.votes++;}
                        }
                        if(bandImage!=cut)bandImage.recycle();
                    }
                }
                List<Guess> sorted=new ArrayList<>(guesses.values());sorted.sort((x,y)->Integer.compare(y.votes,x.votes));
                final MeterVision.Region fallback=first;final Bitmap fallbackPreview=firstPreview;final String trace=debug.toString();
                runOnUiThread(()->{
                    if(isDestroyed())return;setBusy(false);ocr=trace;raw.setText("Результаты проходов:\n"+trace);candidates.removeAllViews();
                    if(sorted.isEmpty()){
                        if(automatic&&fallback!=null)photo.select(fallback.box);cropPreview.setImageBitmap(fallbackPreview);
                        status.setText(fallback==null?"Не удалось найти окошко. Снимите крупнее или выделите цифры пальцем.":"Окошко найдено, но целиком прочитать цифры не удалось. Проверьте число разрядов, рамку и резкость фото.");return;
                    }
                    Guess best=sorted.get(0);if(automatic)photo.select(best.region.box);cropPreview.setImageBitmap(best.preview);
                    boolean conflict=sorted.stream().anyMatch(g->!g.value.equals(best.value));
                    boolean agreed=best.votes>=3&&!conflict;
                    if(agreed)value.setText(best.value);
                    status.setText(agreed?"Несколько проходов совпали. Сверьте цифры и рамку перед сохранением.":"Есть сомнения: выберите и проверьте вариант. Автоподстановка отключена.");
                    Set<String> shown=new HashSet<>();for(Guess g:sorted){if(!shown.add(g.value))continue;Button option=new Button(this);option.setText(g.value+" · совпало проходов: "+g.votes+"/6");candidates.addView(option);option.setOnClickListener(v->{value.setText(g.value);cropPreview.setImageBitmap(g.preview);if(automatic)photo.select(g.region.box);});}
                });
            }catch(Exception|LinkageError e){runOnUiThread(()->{if(isDestroyed())return;setBusy(false);status.setText("Не удалось завершить распознавание. Попробуйте другое фото или введите показание вручную.");});}
        });
    }
    private static String integerPart(String number) {
        return number.split("[.,]",2)[0].replaceFirst("^0+(?!$)","");
    }
    private JSONArray readings() { try{return new JSONArray(getPreferences(0).getString("readings","[]"));}catch(JSONException e){throw new IllegalStateException("Повреждена история",e);} }
    private void saveReading() {
        String n=name.getText().toString().trim(), v=value.getText().toString().trim().replace(',','.');
        if(n.isEmpty()){name.setError("Укажите название");return;}
        if(!v.matches("\\d{1,12}(\\.\\d{1,6})?")){value.setError("Введите показание цифрами, например 124");return;}
        v=integerPart(v); value.setText(v);
        try { JSONObject r=new JSONObject(); r.put("id",UUID.randomUUID().toString()); r.put("meter_name",n); r.put("resource",new String[]{"water","electricity","gas"}[kind.getSelectedItemPosition()]); r.put("unit",kind.getSelectedItemPosition()==1?"kWh":"m3"); r.put("tariff",tariff.getSelectedItemPosition()==0?JSONObject.NULL:"T"+tariff.getSelectedItemPosition()); r.put("value",v); r.put("captured_at",java.time.Instant.now().toString()); r.put("ocr_text",ocr); r.put("confirmed_by_user",true);
            JSONArray all=readings(); all.put(r);
            if(!getPreferences(0).edit().putString("readings",all.toString()).commit())throw new IOException();
            status.setText("Показание сохранено на телефоне");
        }catch(Exception e){status.setText("Не удалось сохранить показание");}
    }
    private void history() {
        try { JSONArray all=readings(); String json=all.toString(2); new AlertDialog.Builder(this).setTitle("Сохранено: "+all.length()).setMessage(json).setPositiveButton("Закрыть",null).setNeutralButton("Поделиться JSON",(d,w)->{ Intent i=new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT,json); startActivity(Intent.createChooser(i,"Экспорт показаний")); }).show();
        }catch(Exception e){status.setText("Не удалось открыть историю");}
    }
    @Override protected void onDestroy(){super.onDestroy(); disposed=true; worker.shutdownNow(); engine.close();}
    private class CropView extends View {
        Bitmap bitmap; final RectF bounds=new RectF(), selected=new RectF(); float sx,sy; final Paint paint=new Paint(3);
        CropView(){super(MainActivity.this);setContentDescription("Фотография счётчика. Проведите пальцем для выделения цифр.");}
        void setBitmap(Bitmap b){bitmap=b;selected.setEmpty();invalidate();}
        void select(android.graphics.Rect r){if(bitmap==null||bounds.isEmpty())return;float scale=bounds.width()/bitmap.getWidth();selected.set(bounds.left+r.left*scale,bounds.top+r.top*scale,bounds.left+r.right*scale,bounds.top+r.bottom*scale);invalidate();}
        @Override protected void onDraw(Canvas c){c.drawColor(Color.rgb(231,237,240));if(bitmap==null)return;float scale=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;bounds.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);paint.setStyle(Paint.Style.FILL);c.drawBitmap(bitmap,null,bounds,paint);if(!selected.isEmpty()){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));paint.setColor(Color.rgb(0,150,130));c.drawRect(selected,paint);paint.setStyle(Paint.Style.FILL);}}
        float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
        @Override public boolean onTouchEvent(android.view.MotionEvent e){if(!isEnabled()||bitmap==null)return false;float x=clamp(e.getX(),bounds.left,bounds.right),y=clamp(e.getY(),bounds.top,bounds.bottom);switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=x;sy=y;selected.setEmpty();getParent().requestDisallowInterceptTouchEvent(true);return true;case MotionEvent.ACTION_MOVE:selected.set(Math.min(sx,x),Math.min(sy,y),Math.max(sx,x),Math.max(sy,y));invalidate();return true;case MotionEvent.ACTION_UP:if(selected.width()<dp(8)||selected.height()<dp(8))selected.setEmpty();getParent().requestDisallowInterceptTouchEvent(false);invalidateResult();invalidate();performClick();return true;case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
        Bitmap cropped(){if(selected.isEmpty()||bounds.isEmpty())return bitmap;float scale=bitmap.getWidth()/bounds.width();int x=Math.max(0,(int)((selected.left-bounds.left)*scale)),y=Math.max(0,(int)((selected.top-bounds.top)*scale));int w=Math.min(bitmap.getWidth()-x,Math.max(1,(int)(selected.width()*scale))),h=Math.min(bitmap.getHeight()-y,Math.max(1,(int)(selected.height()*scale)));return Bitmap.createBitmap(bitmap,x,y,w,h);}
    }
}
