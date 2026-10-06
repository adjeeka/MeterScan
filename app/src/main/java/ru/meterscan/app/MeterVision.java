package ru.meterscan.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Local candidate detection. Scores rank geometry; they are not OCR probabilities. */
final class MeterVision {
    static final class Region {
        final Rect box; final double angle, score;
        Region(Rect b,double a,double s){box=b;angle=a;score=s;}
    }
    static List<Region> locate(Bitmap bitmap,int digits) {
        if(!OpenCVLoader.initLocal())throw new IllegalStateException("OpenCV unavailable");
        int w=bitmap.getWidth(),h=bitmap.getHeight(); double scale=Math.min(1,1280.0/Math.max(w,h));
        Bitmap small=Bitmap.createScaledBitmap(bitmap,(int)(w*scale),(int)(h*scale),true);
        Mat rgba=new Mat(),gray=new Mat(),mask=new Mat(); List<Region> result=new ArrayList<>();
        try {
            Utils.bitmapToMat(small,rgba); Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);
            int sw=small.getWidth(),sh=small.getHeight();int[] px=new int[sw*sh];byte[] red=new byte[px.length];small.getPixels(px,0,sw,0,0,sw,sh);
            for(int i=0;i<px.length;i++){int c=px[i],r=Color.red(c),g=Color.green(c),b=Color.blue(c);if(r>g+18&&r>b+12&&r>g*1.1)red[i]=(byte)255;}
            mask.create(sh,sw,CvType.CV_8UC1);mask.put(0,0,red);
            List<org.opencv.core.Rect> redDigits=new ArrayList<>();
            for(org.opencv.core.Rect r:contours(mask))if(r.height>Math.max(12,sh*.02)&&r.height<sh*.18&&r.width>.18*r.height&&r.width<r.height)redDigits.add(r);
            redDigits.sort((a,b)->Integer.compare(b.height,a.height));
            if(redDigits.size()>250)redDigits=new ArrayList<>(redDigits.subList(0,250));
            // Repeated red digits mark the fractional suffix. Find aligned neighbours,
            // then infer the whole-number cells immediately to their left.
            for(org.opencv.core.Rect a:redDigits)for(org.opencv.core.Rect b:redDigits){
                double ax=a.x+a.width*.5,bx=b.x+b.width*.5,ay=a.y+a.height*.5,by=b.y+b.height*.5;
                double pitch=bx-ax, height=(a.height+b.height)*.5;
                if(pitch<height*.55||pitch>height*1.8||Math.abs(ay-by)>height*.32||Math.min(a.height,b.height)<Math.max(a.height,b.height)*.65)continue;
                // A pair inside the last two red cells must not shift the reading by one digit.
                boolean previous=false; int aligned=2;
                for(org.opencv.core.Rect c:redDigits){double cx=c.x+c.width*.5,cy=c.y+c.height*.5;
                    if(Math.abs(cx-(ax-pitch))<pitch*.25&&Math.abs(cy-(ay-(by-ay)))<height*.3&&c.height>height*.6)previous=true;
                    if(Math.abs(cx-(bx+pitch))<pitch*.3&&Math.abs(cy-(by+(by-ay)))<height*.4&&c.height>height*.6)aligned=3;
                }
                if(previous)continue;
                double slope=(by-ay)/pitch, right=ax-pitch*.5, left=right-digits*pitch;
                double center=ay-slope*pitch*(digits+1)*.5;
                double top=center-height*.63-Math.abs(slope)*digits*pitch*.5;
                double bottom=center+height*.63+Math.abs(slope)*digits*pitch*.5;
                if(left<0||right>sw||top<0||bottom>sh)continue;
                // Reject red logos whose inferred left-hand area contains no dark strokes.
                org.opencv.core.Rect probe=new org.opencv.core.Rect((int)left,(int)top,(int)(right-left),(int)(bottom-top));
                Mat roi=gray.submat(probe), dark=new Mat();Imgproc.threshold(roi,dark,110,255,Imgproc.THRESH_BINARY_INV);
                double fraction=Core.countNonZero(dark)/(double)probe.area();roi.release();dark.release();
                if(fraction<.015||fraction>.7)continue;
                result.add(new Region(new Rect((int)(left/scale),(int)(top/scale),(int)(right/scale),(int)(bottom/scale)),Math.toDegrees(Math.atan(slope)),100+aligned*10+height/sh*10));
            }
            result.sort((a,b)->Double.compare(b.score,a.score));
            List<Region> unique=new ArrayList<>();for(Region r:result){boolean duplicate=false;for(Region e:unique)if(overlap(r.box,e.box)>.6)duplicate=true;if(!duplicate)unique.add(r);if(unique.size()==4)break;}
            return unique;
        } finally {rgba.release();gray.release();mask.release();if(small!=bitmap)small.recycle();}
    }
    private static List<org.opencv.core.Rect> contours(Mat image){List<MatOfPoint> cs=new ArrayList<>();Mat hierarchy=new Mat(),copy=image.clone();List<org.opencv.core.Rect> out=new ArrayList<>();try{Imgproc.findContours(copy,cs,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);for(MatOfPoint c:cs)out.add(Imgproc.boundingRect(c));return out;}finally{for(Mat c:cs)c.release();copy.release();hierarchy.release();}}
    private static double overlap(Rect a,Rect b){Rect x=new Rect(a);if(!x.intersect(b))return 0;return x.width()*(double)x.height()/Math.min(a.width()*(double)a.height(),b.width()*(double)b.height());}
    static Bitmap crop(Bitmap input,Region r){
        Rect b=r.box;Bitmap cut=Bitmap.createBitmap(input,b.left,b.top,b.width(),b.height());
        if(Math.abs(r.angle)<1)return cut;
        Mat src=new Mat(),dst=new Mat();Utils.bitmapToMat(cut,src);Mat transform=Imgproc.getRotationMatrix2D(new Point(src.cols()/2.0,src.rows()/2.0),r.angle,1);
        Imgproc.warpAffine(src,dst,transform,src.size(),Imgproc.INTER_CUBIC,Core.BORDER_CONSTANT,new Scalar(255,255,255,255));
        Bitmap out=Bitmap.createBitmap(dst.cols(),dst.rows(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(dst,out);src.release();dst.release();transform.release();if(cut!=input)cut.recycle();return out;
    }
    static Bitmap variant(Bitmap input,int mode){
        int targetH=160;Bitmap scaled=Bitmap.createScaledBitmap(input,Math.min(1800,Math.max(100,input.getWidth()*targetH/input.getHeight())),targetH,true);
        int w=scaled.getWidth(),h=scaled.getHeight();int[] pixels=new int[w*h];scaled.getPixels(pixels,0,w,0,0,w,h);
        // Red channel makes red ink pale without punching white holes into dark strokes.
        for(int i=0;i<pixels.length;i++){int c=pixels[i];int v=Math.max(Color.red(c),(Color.red(c)+Color.green(c)+Color.blue(c))/3);pixels[i]=Color.rgb(v,v,v);}
        Bitmap grayBmp=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);if(scaled!=input)scaled.recycle();
        Mat rgba=new Mat(),g=new Mat(),out=new Mat();Utils.bitmapToMat(grayBmp,rgba);Imgproc.cvtColor(rgba,g,Imgproc.COLOR_RGBA2GRAY);
        if(mode==0){org.opencv.imgproc.CLAHE clahe=Imgproc.createCLAHE(2,new Size(8,8));clahe.apply(g,out);clahe.collectGarbage();clahe.clear();}else if(mode==1){Imgproc.threshold(g,out,0,255,Imgproc.THRESH_BINARY|Imgproc.THRESH_OTSU);}else{Imgproc.adaptiveThreshold(g,out,255,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY,41,12);}
        Mat padded=new Mat();Core.copyMakeBorder(out,padded,24,24,24,24,Core.BORDER_CONSTANT,new Scalar(255));Bitmap result=Bitmap.createBitmap(padded.cols(),padded.rows(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(padded,result);
        rgba.release();g.release();out.release();padded.release();grayBmp.recycle();return result;
    }
}
