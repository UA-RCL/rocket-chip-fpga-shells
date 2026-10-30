package sifive.fpgashells.shell.xilinx

import chisel3._
import chisel3.experimental.dataview._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import org.chipsalliance.cde.config._
import sifive.fpgashells.clocks._
import sifive.fpgashells.devices.xilinx.xilinxvcu128mig._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.ip.xilinx.vcu128mig._
import sifive.fpgashells.shell._

// Pin locations are from the Vivado VCU128 board files (xilinx.com:vcu128:part0:1.0)

class SysClockVCU128PlacedOverlay(val shell: VCU128ShellBasicOverlays, name: String, val designInput: ClockInputDesignInput, val shellInput: ClockInputShellInput)
  extends LVDSClockInputXilinxPlacedOverlay(name, designInput, shellInput)
{
  val node = shell { ClockSourceNode(freqMHz = 100, jitterPS = 50)(ValName(name)) }

  shell { InModuleBody {
    shell.xdc.addPackagePin(io.p, "BH51")
    shell.xdc.addPackagePin(io.n, "BJ51")
    shell.xdc.addIOStandard(io.p, "DIFF_SSTL12")
    shell.xdc.addIOStandard(io.n, "DIFF_SSTL12")
  } }
}
class SysClockVCU128ShellPlacer(shell: VCU128ShellBasicOverlays, val shellInput: ClockInputShellInput)(implicit val valName: ValName)
  extends ClockInputShellPlacer[VCU128ShellBasicOverlays]
{
    def place(designInput: ClockInputDesignInput) = new SysClockVCU128PlacedOverlay(shell, valName.name, designInput, shellInput)
}

// UART0 and UART1 of the on-board FTDI USB-UART/JTAG bridge are wired to the FPGA: index 0 is UART0, index 1 is UART1
object UARTVCU128PinConstraints {
  //                 ctsn,   rtsn,   rxd,    txd
  val pins = Seq(Seq("BP22", "BP23", "BP26", "BN26"),
                 Seq("BL26", "BL27", "BK28", "BJ28"))
}
class UARTVCU128PlacedOverlay(val shell: VCU128ShellBasicOverlays, name: String, val designInput: UARTDesignInput, val shellInput: UARTShellInput)
  extends UARTXilinxPlacedOverlay(name, designInput, shellInput, true)
{
  shell { InModuleBody {
    val packagePinsWithPackageIOs = UARTVCU128PinConstraints.pins(shellInput.index) zip
                                    Seq(IOPin(io.ctsn.get), IOPin(io.rtsn.get), IOPin(io.rxd), IOPin(io.txd))

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS18")
      shell.xdc.addIOB(io)
    } }
  } }
}
class UARTVCU128ShellPlacer(shell: VCU128ShellBasicOverlays, val shellInput: UARTShellInput)(implicit val valName: ValName)
  extends UARTShellPlacer[VCU128ShellBasicOverlays] {
  def place(designInput: UARTDesignInput) = new UARTVCU128PlacedOverlay(shell, valName.name, designInput, shellInput)
}

object LEDVCU128PinConstraints {
  val pins = Seq("BH24", "BG24", "BG25", "BF25", "BF26", "BF27", "BG27", "BG28")
}
class LEDVCU128PlacedOverlay(val shell: VCU128ShellBasicOverlays, name: String, val designInput: LEDDesignInput, val shellInput: LEDShellInput)
  extends LEDXilinxPlacedOverlay(name, designInput, shellInput, packagePin = Some(LEDVCU128PinConstraints.pins(shellInput.number)), ioStandard = "LVCMOS18")
class LEDVCU128ShellPlacer(shell: VCU128ShellBasicOverlays, val shellInput: LEDShellInput)(implicit val valName: ValName)
  extends LEDShellPlacer[VCU128ShellBasicOverlays] {
  def place(designInput: LEDDesignInput) = new LEDVCU128PlacedOverlay(shell, valName.name, designInput, shellInput)
}

class JTAGDebugBScanVCU128PlacedOverlay(val shell: VCU128ShellBasicOverlays, name: String, val designInput: JTAGDebugBScanDesignInput, val shellInput: JTAGDebugBScanShellInput)
  extends JTAGDebugBScanXilinxPlacedOverlay(name, designInput, shellInput)
class JTAGDebugBScanVCU128ShellPlacer(val shell: VCU128ShellBasicOverlays, val shellInput: JTAGDebugBScanShellInput)(implicit val valName: ValName)
  extends JTAGDebugBScanShellPlacer[VCU128ShellBasicOverlays] {
  def place(designInput: JTAGDebugBScanDesignInput) = new JTAGDebugBScanVCU128PlacedOverlay(shell, valName.name, designInput, shellInput)
}

case object VCU128DDRSize extends Field[BigInt](0x100000000L) // 4GB
class DDRVCU128PlacedOverlay(val shell: VCU128ShellBasicOverlays, name: String, val designInput: DDRDesignInput, val shellInput: DDRShellInput)
  extends DDRPlacedOverlay[XilinxVCU128MIGPads](name, designInput, shellInput)
{
  val size = p(VCU128DDRSize)

  val migParams = XilinxVCU128MIGParams(address = AddressSet.misaligned(di.baseAddress, size))
  val mig = LazyModule(new XilinxVCU128MIG(migParams))
  val ddrUI     = shell { ClockSourceNode(freqMHz = 333.333) } // DDR4-2666, 4:1 PHY ratio
  val areset    = shell { ClockSinkNode(Seq(ClockSinkParameters())) }
  areset := designInput.wrangler := ddrUI

  def overlayOutput = DDROverlayOutput(ddr = mig.node)
  def ioFactory = new XilinxVCU128MIGPads(size)

  shell { InModuleBody {
    require (shell.sys_clock.get.isDefined, "Use of DDRVCU128Overlay depends on SysClockVCU128Overlay")
    val (sys, _) = shell.sys_clock.get.get.overlayOutput.node.out(0)
    val (ui, _) = ddrUI.out(0)
    val (ar, _) = areset.in(0)
    val port = mig.module.io.port
    io <> port.viewAsSupertype(new VCU128MIGIODDR(mig.depth))
    ui.clock := port.c0_ddr4_ui_clk
    ui.reset := /*!port.mmcm_locked ||*/ port.c0_ddr4_ui_clk_sync_rst
    port.c0_sys_clk_i := sys.clock.asUInt
    port.sys_rst := sys.reset // pllReset
    port.c0_ddr4_aresetn := !(ar.reset.asBool)

    // adr[0:16], bg, ba[0:1], reset_n, act_n, ck_c, ck_t, cke, cs_n[0:1], odt, dq[0:71], dqs_c[0:8], dqs_t[0:8], dm_dbi_n[0:8]
    val allddrpins = Seq(
      "BF50", "BD51", "BG48", "BE50", "BE49", "BE51", "BF53", "BG50",
      "BF51", "BG47", "BF47", "BG49", "BF48", "BF52", "BG53", "BH54",
      "BJ54", "BG54", "BE54", "BE53", "BH50", "BG52", "BK54", "BK53",
      "BH52", "BP49", "BK48", "BH49", "BM45", "BP44", "BP47", "BN45",
      "BM44", "BN44", "BN47", "BP43", "BL45", "BK44", "BL46", "BK43",
      "BL43", "BJ44", "BL42", "BJ43", "BK41", "BG44", "BG42", "BH44",
      "BH45", "BG45", "BG43", "BJ41", "BE43", "BF42", "BC42", "BF43",
      "BD42", "BF45", "BE44", "BF46", "BP32", "BP29", "BP31", "BP28",
      "BN32", "BM30", "BN31", "BL30", "BL32", "BP34", "BN34", "BK33",
      "BL31", "BL33", "BM33", "BK31", "BJ34", "BG35", "BH34", "BH35",
      "BJ33", "BF35", "BG34", "BF36", "BF31", "BH30", "BJ31", "BG32",
      "BH31", "BF32", "BH29", "BF33", "BN51", "BM52", "BN50", "BL52",
      "BM48", "BL53", "BN49", "BL51", "BP46", "BK46", "BJ46", "BE46",
      "BN30", "BM35", "BK35", "BK30", "BM50", "BN46", "BK45", "BH46",
      "BE45", "BN29", "BL35", "BK34", "BJ29", "BM49", "BN42", "BL47",
      "BH42", "BD41", "BM28", "BM34", "BH32", "BG29", "BP48")

    (IOPin.of(io) zip allddrpins) foreach { case (io, pin) => shell.xdc.addPackagePin(io, pin) }
  } }

  shell.sdc.addGroup(pins = Seq(mig.island.module.blackbox.io.c0_ddr4_ui_clk))
}
class DDRVCU128ShellPlacer(shell: VCU128ShellBasicOverlays, val shellInput: DDRShellInput)(implicit val valName: ValName)
  extends DDRShellPlacer[VCU128ShellBasicOverlays] {
  def place(designInput: DDRDesignInput) = new DDRVCU128PlacedOverlay(shell, valName.name, designInput, shellInput)
}

abstract class VCU128ShellBasicOverlays()(implicit p: Parameters) extends UltraScaleShell{
  // PLL reset causes
  val pllReset = InModuleBody { Wire(Bool()) }

  val sys_clock = Overlay(ClockInputOverlayKey, new SysClockVCU128ShellPlacer(this, ClockInputShellInput()))
  val led       = Seq.tabulate(8)(i => Overlay(LEDOverlayKey, new LEDVCU128ShellPlacer(this, LEDShellInput(color = "red", number = i))(valName = ValName(s"led_$i"))))
  val ddr       = Overlay(DDROverlayKey, new DDRVCU128ShellPlacer(this, DDRShellInput()))
}

class VCU128Shell()(implicit p: Parameters) extends VCU128ShellBasicOverlays
{
  // Order matters; ddr depends on sys_clock
  val uart      = Seq.tabulate(2)(i => Overlay(UARTOverlayKey, new UARTVCU128ShellPlacer(this, UARTShellInput(index = i))(valName = ValName(s"uart_$i"))))
  val jtagBScan = Overlay(JTAGDebugBScanOverlayKey, new JTAGDebugBScanVCU128ShellPlacer(this, JTAGDebugBScanShellInput()))

  val topDesign = LazyModule(p(DesignKey)(designParameters))

  // Place the sys_clock at the Shell if the user didn't ask for it
  designParameters(ClockInputOverlayKey).foreach { unused =>
    val source = unused.place(ClockInputDesignInput()).overlayOutput.node
    val sink = ClockSinkNode(Seq(ClockSinkParameters()))
    sink := source
  }

  override lazy val module = new LazyRawModuleImp(this) {
    val reset = IO(Input(Bool()))
    xdc.addPackagePin(reset, "BM29")
    xdc.addIOStandard(reset, "LVCMOS12")

    val reset_ibuf = Module(new IBUF)
    reset_ibuf.io.I := reset

    val sysclk: Clock = sys_clock.get() match {
      case Some(x: SysClockVCU128PlacedOverlay) => x.clock
    }

    val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
    sdc.addAsyncPath(Seq(powerOnReset))

    pllReset := (reset_ibuf.io.O || powerOnReset)
  }
}

/*
   Copyright 2016 SiFive, Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
