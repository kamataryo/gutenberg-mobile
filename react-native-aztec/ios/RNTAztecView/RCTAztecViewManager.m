#import <React/RCTViewManager.h>

@interface RCT_EXTERN_MODULE(RCTAztecViewManager, NSObject)

RCT_REMAP_VIEW_PROPERTY(text, contents, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(onContentSizeChange, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onBackspace, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onChange, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onEnter, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onFocus, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onBlur, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onPaste, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onSelectionChange, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(blockType, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(activeFormats, NSSet)

RCT_EXPORT_VIEW_PROPERTY(onActiveFormatsChange, RCTBubblingEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onActiveFormatAttributesChange, RCTBubblingEventBlock)

RCT_EXPORT_VIEW_PROPERTY(placeholder, NSString)
RCT_EXPORT_VIEW_PROPERTY(placeholderTextColor, UIColor)
RCT_REMAP_VIEW_PROPERTY(color, textColor, UIColor)
RCT_EXPORT_VIEW_PROPERTY(linkTextColor, UIColor)

RCT_EXPORT_VIEW_PROPERTY(fontFamily, NSString)
RCT_EXPORT_VIEW_PROPERTY(fontSize, CGFloat)
RCT_EXPORT_VIEW_PROPERTY(fontWeight, NSString)

RCT_EXPORT_VIEW_PROPERTY(disableEditingMenu, BOOL)
RCT_REMAP_VIEW_PROPERTY(textAlign, textAlignment, NSTextAlignment)

RCT_EXTERN_METHOD(applyFormat:(nonnull NSNumber *)node format:(NSString *)format)
RCT_EXTERN_METHOD(setLink:(nonnull NSNumber *)node url:(nonnull NSString *)url title:(nullable NSString *)title)
RCT_EXTERN_METHOD(removeLink:(nonnull NSNumber *)node)

@end
