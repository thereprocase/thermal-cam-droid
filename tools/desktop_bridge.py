#!/usr/bin/env python3
"""Experimental private-network radiometric bridge for the documented UVC device.

Frames remain lossless 256x384 YUYV composites. This endpoint is not an MJPEG
temperature decoder. Bind it only to a trusted LAN/Tailnet interface; the
prototype does not authenticate clients. Persistent device identifiers are
excluded from its state reply and logs.
"""
import argparse
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

FRAME_BYTES=196608


class Capture:
    def __init__(self,device):
        node=Path('/sys/class/video4linux')/Path(device).name
        supported=False
        for parent in node.resolve().parents:
            try:
                if (parent/'idVendor').read_text().strip()=='0bda' and (parent/'idProduct').read_text().strip()=='5830':supported=True;break
            except OSError:pass
        if not supported:raise RuntimeError('This bridge supports the documented 0bda:5830 UVC device; select its capture video node')
        self.condition=threading.Condition();self.frame=None;self.sequence=0;self.stamp=0;self.error=None
        self.process=subprocess.Popen(['ffmpeg','-hide_banner','-loglevel','error','-f','v4l2',
            '-input_format','yuyv422','-video_size','256x384','-framerate','25','-i',device,
            '-c:v','copy','-f','rawvideo','pipe:1'],stdout=subprocess.PIPE)
        threading.Thread(target=self.run,daemon=True).start()

    def run(self):
        while True:
            frame=self.process.stdout.read(FRAME_BYTES)
            with self.condition:
                if len(frame)!=FRAME_BYTES:
                    self.error='Camera stream ended or returned an incomplete frame';self.condition.notify_all();return
                self.frame=frame;self.sequence+=1;self.stamp=time.time_ns();self.condition.notify_all()

    def close(self):
        self.process.terminate()
        try:self.process.wait(timeout=2)
        except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=2)


class Controls:
    def __init__(self,log_path):
        from p2probe import Camera
        from p2probe.usb import Usb
        self.lock=threading.Lock();self.usb=Usb(log_path);self.camera=Camera(self.usb,ready_timeout=10)
        from p2probe import Property
        expected=[32,300,300,128,128,1]
        self.properties={}
        for p in Property:
            if self.camera.get_property(p)!=expected[p]:self.camera.set_property(p,expected[p])
            self.properties[p.name]=self.camera.get_property(p)
    def command(self,action,high):
        from p2probe import Property
        with self.lock:
            if action=='nuc':self.camera.nuc()
            elif action=='gain':
                self.camera.set_property(Property.HIGH_GAIN,int(high));self.properties['HIGH_GAIN']=self.camera.get_property(Property.HIGH_GAIN)
                if self.properties['HIGH_GAIN']!=int(high):raise RuntimeError('Gain readback differs from request')
            else:raise ValueError('Unsupported control action')


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        if self.path=='/state':
            controls=self.server.controls
            data=json.dumps({'protocol':'thermal-field-v1','controls':controls is not None,
                             'properties':controls.properties if controls else None,
                             'physical_baseline_verified':False}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if self.path!='/radiometric':self.send_error(404);return
        self.send_response(200)
        self.send_header('Content-Type','multipart/x-mixed-replace; boundary=thermal-field')
        self.send_header('X-Thermal-Protocol','thermal-field-v1')
        self.send_header('X-Thermal-Format','yuyv-256x384-u16le-k64')
        self.send_header('Cache-Control','no-store');self.end_headers()
        sequence=0
        try:
            while True:
                capture=self.server.capture
                with capture.condition:
                    capture.condition.wait_for(lambda:capture.sequence!=sequence or capture.error,timeout=2)
                    if capture.error:return
                    if capture.sequence==sequence:continue
                    frame=capture.frame;sequence=capture.sequence;stamp=capture.stamp
                header=f'--thermal-field\r\nContent-Type: application/octet-stream\r\nContent-Length: {FRAME_BYTES}\r\nX-Frame-Number: {sequence}\r\nX-Capture-Unix-Ns: {stamp}\r\n\r\n'.encode()
                self.wfile.write(header);self.wfile.write(frame);self.wfile.write(b'\r\n');self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError):pass
    def do_POST(self):
        if self.path!='/control':self.send_error(404);return
        if self.server.controls is None:self.send_error(503,'USB controls unavailable on bridge');return
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<1024:raise ValueError('Invalid command size')
            data=json.loads(self.rfile.read(length));action=data.get('action');high=data.get('high',True)
            if action not in ['nuc','gain'] or not isinstance(high,bool):raise ValueError('Invalid control command')
            self.server.controls.command(action,high)
            self.send_response(204);self.end_headers()
        except Exception:
            self.send_error(503,'Control command failed; inspect bridge locally')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--bind',default='127.0.0.1');parser.add_argument('--port',type=int,default=8008)
    parser.add_argument('--device',default='/dev/video0');parser.add_argument('--control-log',default='/tmp/thermal-field-controls.jsonl')
    args=parser.parse_args()
    server=ThreadingHTTPServer((args.bind,args.port),Handler);server.daemon_threads=True
    server.capture=Capture(args.device)
    try:server.controls=Controls(args.control_log)
    except Exception:server.controls=None;print('Raw stream enabled; USB controls unavailable with current device access.',flush=True)
    print(f'Radiometric bridge: http://{args.bind}:{args.port}/radiometric',flush=True)
    try:server.serve_forever()
    finally:
        server.capture.close()
        if server.controls:server.controls.usb.close()
        server.server_close()
