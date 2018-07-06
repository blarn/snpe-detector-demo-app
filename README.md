# AI Hackable Object Detector - HackMobile 2018 Demo
Demo APP with MobileNet-SSD on SNPE.


#### Compatible with
 * (GPU Accelerated) QRD 8998, QRD 710, Galaxy S8/S8+, Galaxy S9
 * (CPU only) Pixel, Pixel 2, HTC One M8

Likely working on any Android M+ phone. DSP acceleration is not available in this version, will likely be available in a month.


## Windows USB drivers for QRD 8998
Go to this network folder: ```\\cold\tools\Installers\USB\USB_WWAN_WINDOWS(NewSeries)\1.00.52\Internal```
(copy and paste it in the windows explorer).

Run setup.exe and reboot your laptop.

Now the QRD 8998 will be recognized from ADB and Android Studio.

Note: If you're useing a personal device, Google smartphones should be recognized by default by Android Studio. For Samsung devices, use [this driver](https://developer.samsung.com/galaxy/others/android-usb-driver-for-windows).
