return ui.app({ title: "JS POC" },
  ui.column({ spacing: 10 },
    ui.text({ text: "QuickJS engine works", size: 22 }),
    ui.text({ text: "rendered via ui tree", size: 14 })
  )
);
