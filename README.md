# MicroPython Workspace (Seeed XIAO nRF52840)

This workspace is set up to use `mpremote` from a local Python virtual environment.

## 1) Activate environment

```bash
source "/Users/seanlim/Camb /GM1 /.venv/bin/activate"
```

## 2) Install dependencies (if needed)

```bash
pip install -r requirements.txt
```

## 3) Check tool versions

```bash
mpremote --version
python --version
```

## 4) Find your board serial port

```bash
mpremote devs
```

On macOS, your XIAO is commonly something like:
- `/dev/tty.usbmodem*`
- `/dev/tty.usbserial*`

## 5) Open REPL

```bash
mpremote connect auto repl
```

Exit REPL with `Ctrl+]`.

## 6) Deploy code to board

Copy local files to MicroPython device:

```bash
mpremote connect auto fs cp main.py :main.py
```

Copy multiple files:

```bash
mpremote connect auto fs cp main.py boot.py lib/ :/
```

List files on the device:

```bash
mpremote connect auto fs ls
```

Remove a file on the device:

```bash
mpremote connect auto fs rm main.py
```

## 7) Run a local script on device (without copying permanently)

```bash
mpremote connect auto run main.py
```

## 8) Optional: use explicit port

If `auto` picks the wrong device:

```bash
mpremote connect /dev/tty.usbmodemXXXX repl
```

## Notes for XIAO nRF52840

- Ensure MicroPython firmware is flashed before using `mpremote`.
- Use a data-capable USB cable.
- If the board is not detected, unplug/replug and run `mpremote devs` again.
